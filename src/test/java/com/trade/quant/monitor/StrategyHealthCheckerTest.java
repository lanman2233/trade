package com.trade.quant.monitor;

import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.Interval;
import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Symbol;
import com.trade.quant.strategy.Strategy;
import com.trade.quant.strategy.StrategyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StrategyHealthChecker 单元测试
 * 验证策略自动禁用、降级和恢复逻辑
 */
class StrategyHealthCheckerTest {

    private RollingEVCalculator evCalculator;
    private HealthCheckConfig config;
    private StrategyHealthChecker healthChecker;
    private static final String STRATEGY_ID = "TestStrategy-BTC-USDT-5m";

    @BeforeEach
    void setUp() {
        // 清理状态文件，确保测试间隔离
        File stateFile = new File("data/monitor/health-state.json");
        if (stateFile.exists()) {
            stateFile.delete();
        }

        evCalculator = new RollingEVCalculator(100);

        // 使用较宽松的配置用于测试
        config = HealthCheckConfig.builder()
            .enabled(true)
            .autoEnable(true)
            .minSampleSize(5)              // 测试用：5笔交易后开始检查
            .minEV(BigDecimal.ZERO)
            .maxConsecutiveLosses(3)       // 测试用：3笔连续亏损触发禁用
            .minEVNegativeTrades(10)       // 测试用：10笔交易后检查负EV
            .build();

        healthChecker = new StrategyHealthChecker(evCalculator, config);
    }

    /**
     * 测试自动禁用：连续亏损超过阈值
     */
    @Test
    void testAutoDisableOnConsecutiveLosses() {
        // 注册策略
        Strategy strategy = createTestStrategy(STRATEGY_ID);
        healthChecker.registerStrategy(strategy);

        // 添加 1 笔盈利（达到 minSampleSize），然后 4 笔连续亏损（超过阈值 3）
        evCalculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));  // 第1笔：盈利
        healthChecker.checkStrategyHealth(STRATEGY_ID);  // 此时样本不足，跳过

        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));   // 第2笔：亏损
        healthChecker.checkStrategyHealth(STRATEGY_ID);

        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));   // 第3笔：亏损
        healthChecker.checkStrategyHealth(STRATEGY_ID);

        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));   // 第4笔：亏损
        healthChecker.checkStrategyHealth(STRATEGY_ID);

        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));   // 第5笔：亏损
        healthChecker.checkStrategyHealth(STRATEGY_ID);  // 此时样本足够，应该触发禁用

        // 验证策略已被禁用
        assertFalse(healthChecker.isStrategyEnabled(STRATEGY_ID));
        assertEquals(StrategyState.DISABLED, healthChecker.getStrategyState(STRATEGY_ID));
    }

    /**
     * 测试手动重新启用
     */
    @Test
    void testManualReEnable() {
        Strategy strategy = createTestStrategy(STRATEGY_ID);
        healthChecker.registerStrategy(strategy);

        // 添加足够的交易来触发禁用
        evCalculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        for (int i = 0; i < 4; i++) {
            evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
            healthChecker.checkStrategyHealth(STRATEGY_ID);
        }

        assertFalse(healthChecker.isStrategyEnabled(STRATEGY_ID));

        // 手动重新启用
        healthChecker.enableStrategy(STRATEGY_ID);

        assertTrue(healthChecker.isStrategyEnabled(STRATEGY_ID));
        assertEquals(StrategyState.ENABLED, healthChecker.getStrategyState(STRATEGY_ID));
    }

    /**
     * 测试降级状态：EV 为负但未达禁用条件
     * 关键：不能有过多连续亏损
     */
    @Test
    void testDegradedStateOnNegativeEV() {
        Strategy strategy = createTestStrategy(STRATEGY_ID);
        healthChecker.registerStrategy(strategy);

        // 添加 12 笔交易：交替盈利和亏损（避免连续亏损），但总体EV为负
        // 模式：盈利50、亏损150、盈利50、亏损150... (6大亏损 vs 6小盈利)
        for (int i = 0; i < 6; i++) {
            evCalculator.addTrade(createWinTrade(BigDecimal.valueOf(50)));
            evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(150)));
        }

        healthChecker.checkStrategyHealth(STRATEGY_ID);

        // 应该是降级状态（但仍可交易）
        assertTrue(healthChecker.isStrategyEnabled(STRATEGY_ID));
        assertEquals(StrategyState.DEGRADED, healthChecker.getStrategyState(STRATEGY_ID));
    }

    /**
     * 测试从降级状态恢复
     */
    @Test
    void testRecoveryFromDegraded() {
        Strategy strategy = createTestStrategy(STRATEGY_ID);
        healthChecker.registerStrategy(strategy);

        // 先触发降级（交替盈亏，负EV，无连续亏损）
        for (int i = 0; i < 6; i++) {
            evCalculator.addTrade(createWinTrade(BigDecimal.valueOf(50)));
            evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(150)));
        }
        healthChecker.checkStrategyHealth(STRATEGY_ID);
        assertEquals(StrategyState.DEGRADED, healthChecker.getStrategyState(STRATEGY_ID));

        // 添加更多盈利交易使EV转正（连续5笔大盈利）
        for (int i = 0; i < 5; i++) {
            evCalculator.addTrade(createWinTrade(BigDecimal.valueOf(200)));
        }
        healthChecker.checkStrategyHealth(STRATEGY_ID);

        // 应该恢复到 ENABLED
        assertEquals(StrategyState.ENABLED, healthChecker.getStrategyState(STRATEGY_ID));
    }

    /**
     * 测试功能标志：禁用时不应检查健康
     */
    @Test
    void testFeatureFlagDisabled() {
        // 创建禁用功能的配置
        HealthCheckConfig disabledConfig = HealthCheckConfig.builder()
            .enabled(false)  // 功能禁用
            .maxConsecutiveLosses(1)
            .minSampleSize(1)
            .build();

        // 创建新的 EV 计算器（避免与测试间共享状态）
        RollingEVCalculator separateEVCalculator = new RollingEVCalculator(100);
        StrategyHealthChecker disabledChecker = new StrategyHealthChecker(separateEVCalculator, disabledConfig);

        Strategy strategy = createTestStrategy(STRATEGY_ID);
        disabledChecker.registerStrategy(strategy);

        // 即使有连续亏损，也不应禁用（功能已禁用）
        separateEVCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        separateEVCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        disabledChecker.checkStrategyHealth(STRATEGY_ID);

        assertTrue(disabledChecker.isStrategyEnabled(STRATEGY_ID));
    }

    /**
     * 测试样本数不足时不检查
     */
    @Test
    void testInsufficientSampleSize() {
        Strategy strategy = createTestStrategy(STRATEGY_ID);
        healthChecker.registerStrategy(strategy);

        // 只添加 2 笔交易（少于 minSampleSize=5）
        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        evCalculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));

        healthChecker.checkStrategyHealth(STRATEGY_ID);

        // 策略应该仍然启用（样本不足，不检查）
        assertTrue(healthChecker.isStrategyEnabled(STRATEGY_ID));
        assertEquals(StrategyState.ENABLED, healthChecker.getStrategyState(STRATEGY_ID));
    }

    /**
     * 测试多策略独立管理
     */
    @Test
    void testMultipleStrategies() {
        String strategy1 = "Strategy1-BTC-USDT-5m";
        String strategy2 = "Strategy2-ETH-USDT-1m";

        Strategy s1 = createTestStrategy(strategy1);
        Strategy s2 = createTestStrategy(strategy2);

        healthChecker.registerStrategy(s1);
        healthChecker.registerStrategy(s2);

        // 策略 1 连续亏损（被禁用） - 需要足够的样本
        for (int i = 0; i < 5; i++) {
            evCalculator.addTrade(createTradeWithId(strategy1, false, BigDecimal.valueOf(50)));
        }
        healthChecker.checkStrategyHealth(strategy1);

        // 策略 2 全部盈利（保持启用）
        for (int i = 0; i < 5; i++) {
            evCalculator.addTrade(createTradeWithId(strategy2, true, BigDecimal.valueOf(100)));
        }
        healthChecker.checkStrategyHealth(strategy2);

        assertFalse(healthChecker.isStrategyEnabled(strategy1));
        assertTrue(healthChecker.isStrategyEnabled(strategy2));

        Map<String, StrategyState> states = healthChecker.getStrategyStates();
        assertEquals(StrategyState.DISABLED, states.get(strategy1));
        assertEquals(StrategyState.ENABLED, states.get(strategy2));
    }

    /**
     * 测试未注册策略默认启用
     */
    @Test
    void testUnregisteredStrategyEnabled() {
        String unknownStrategy = "UnknownStrategy-BTC-USDT-5m";

        // 未注册的策略应该默认启用
        assertTrue(healthChecker.isStrategyEnabled(unknownStrategy));
        assertNull(healthChecker.getStrategyState(unknownStrategy));
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试用的 Strategy stub
     */
    private Strategy createTestStrategy(String strategyId) {
        return new Strategy() {
            private final String id = strategyId;

            @Override
            public String getStrategyId() {
                return id;
            }

            @Override
            public String getName() {
                return "TestStrategy";
            }

            @Override
            public Symbol getSymbol() {
                return Symbol.of("BTC-USDT");
            }

            @Override
            public Interval getInterval() {
                return Interval.FIVE_MINUTES;
            }

            @Override
            public StrategyConfig getConfig() {
                return StrategyConfig.builder().build();
            }

            @Override
            public void setConfig(StrategyConfig config) {}

            @Override
            public void initialize() {}

            @Override
            public boolean isInCooldown() {
                return false;
            }

            @Override
            public long getCooldownRemaining() {
                return 0;
            }

            @Override
            public void reset() {}

            @Override
            public com.trade.quant.strategy.Signal analyze(List<com.trade.quant.core.KLine> kLines) {
                return null;
            }

            @Override
            public com.trade.quant.strategy.Signal onPositionUpdate(com.trade.quant.core.Position position, com.trade.quant.core.KLine currentKLine) {
                return null;
            }
        };
    }

    private ClosedTrade createWinTrade(BigDecimal pnl) {
        return createTrade(true, pnl);
    }

    private ClosedTrade createLossTrade(BigDecimal pnl) {
        return createTrade(false, pnl);
    }

    private ClosedTrade createTrade(boolean isWin, BigDecimal pnl) {
        return createTradeWithId(STRATEGY_ID, isWin, pnl);
    }

    private ClosedTrade createTradeWithId(String strategyId, boolean isWin, BigDecimal pnl) {
        BigDecimal actualPnl = isWin ? pnl : pnl.negate();

        return new ClosedTrade(
            "trade-" + System.currentTimeMillis(),
            Symbol.of("BTC-USDT"),
            PositionSide.LONG,
            BigDecimal.valueOf(50000),
            BigDecimal.valueOf(50100),
            BigDecimal.valueOf(0.1),
            actualPnl,
            BigDecimal.ZERO,
            Instant.now().minusSeconds(3600),
            Instant.now(),
            strategyId
        );
    }
}
