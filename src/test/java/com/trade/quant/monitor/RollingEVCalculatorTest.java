package com.trade.quant.monitor;

import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RollingEVCalculator 单元测试
 * 验证 EV 计算的正确性和边界情况处理
 */
class RollingEVCalculatorTest {

    private RollingEVCalculator calculator;
    private static final String STRATEGY_ID = "TestStrategy-BTC-USDT-5m";
    private static final Symbol SYMBOL = Symbol.of("BTC-USDT");

    @BeforeEach
    void setUp() {
        calculator = new RollingEVCalculator(100);
    }

    /**
     * 测试完美胜率的 EV 计算
     * 50 笔盈利 × $100，50 笔亏损 × $50
     * EV = (0.5 × 100) - (0.5 × 50) = 50 - 25 = 25
     */
    @Test
    void testEVCalculationWithPerfectWinRate() {
        // 添加 100 笔交易：50 赢 50 输
        for (int i = 0; i < 50; i++) {
            calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
            calculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        }

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        assertEquals(100, metrics.getSampleSize());
        assertEquals(new BigDecimal("25.00"), metrics.getRollingEV());
        assertEquals(new BigDecimal("0.5000"), metrics.getWinRate());
        assertEquals(new BigDecimal("100.00"), metrics.getAvgWin());
        assertEquals(new BigDecimal("50.00"), metrics.getAvgLoss());
    }

    /**
     * 测试窗口大小限制
     * 添加 200 笔交易，应该只保留最后 100 笔
     */
    @Test
    void testWindowResize() {
        // 添加 200 笔交易
        for (int i = 0; i < 200; i++) {
            calculator.addTrade(createTrade(i % 2 == 0, BigDecimal.valueOf(100)));
        }

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        // 应该只保留最后 100 笔
        assertEquals(100, metrics.getSampleSize());
    }

    /**
     * 测试连续亏损计算
     * 5 笔盈利，然后 7 笔亏损
     */
    @Test
    void testConsecutiveLossesCalculation() {
        // 先添加 5 笔盈利
        for (int i = 0; i < 5; i++) {
            calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        }

        // 再添加 7 笔亏损
        for (int i = 0; i < 7; i++) {
            calculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        }

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        assertEquals(7, metrics.getConsecutiveLosses());
    }

    /**
     * 测试 BigDecimal 精度
     * 确保没有浮点数精度损失
     */
    @Test
    void testBigDecimalPrecision() {
        // 使用精确的小数
        calculator.addTrade(createWinTrade(new BigDecimal("33.33333333")));
        calculator.addTrade(createLossTrade(new BigDecimal("16.66666667")));

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        // EV = (0.5 × 33.33333333) - (0.5 × 16.66666667)
        //    = 16.666666665 - 8.333333335
        //    = 8.33333333
        assertEquals(new BigDecimal("8.33"), metrics.getRollingEV());
        assertEquals(new BigDecimal("0.5000"), metrics.getWinRate());
    }

    /**
     * 测试空交易历史
     */
    @Test
    void testEmptyTradeHistory() {
        RollingMetrics metrics = calculator.calculateMetrics("NonExistentStrategy");

        assertEquals(0, metrics.getSampleSize());
        assertEquals(BigDecimal.ZERO, metrics.getRollingEV());
        assertFalse(metrics.hasSufficientData(10));
    }

    /**
     * 测试单笔交易
     */
    @Test
    void testSingleTrade() {
        calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        assertEquals(1, metrics.getSampleSize());
        assertEquals(new BigDecimal("100.00"), metrics.getRollingEV());
        assertEquals(new BigDecimal("1.0000"), metrics.getWinRate());
    }

    /**
     * 测试全部亏损
     */
    @Test
    void testAllLosses() {
        for (int i = 0; i < 10; i++) {
            calculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));
        }

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        assertEquals(10, metrics.getSampleSize());
        assertTrue(metrics.getRollingEV().compareTo(BigDecimal.ZERO) < 0);
        assertEquals(new BigDecimal("0.0000"), metrics.getWinRate());
        assertEquals(10, metrics.getConsecutiveLosses());
    }

    /**
     * 测试状态保存和恢复
     */
    @Test
    void testStatePersistence() {
        // 添加一些交易
        for (int i = 0; i < 10; i++) {
            calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        }

        // 保存状态
        Map<String, java.util.List<ClosedTrade>> state = calculator.getState();

        // 创建新的计算器并恢复状态
        RollingEVCalculator newCalculator = new RollingEVCalculator(100);
        newCalculator.restoreState(state);

        // 验证恢复的指标
        RollingMetrics metrics = newCalculator.calculateMetrics(STRATEGY_ID);
        assertEquals(10, metrics.getSampleSize());
        assertEquals(new BigDecimal("100.00"), metrics.getRollingEV());
    }

    /**
     * 测试清空策略历史
     */
    @Test
    void testClearStrategy() {
        calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        calculator.addTrade(createLossTrade(BigDecimal.valueOf(50)));

        assertEquals(2, calculator.calculateMetrics(STRATEGY_ID).getSampleSize());

        calculator.clearStrategy(STRATEGY_ID);

        assertEquals(0, calculator.calculateMetrics(STRATEGY_ID).getSampleSize());
    }

    /**
     * 测试多策略隔离
     */
    @Test
    void testMultiStrategyIsolation() {
        String strategy1 = "Strategy1-BTC-USDT-5m";
        String strategy2 = "Strategy2-ETH-USDT-1m";

        // 策略 1 添加盈利交易
        calculator.addTrade(createTradeWithId(strategy1, true, BigDecimal.valueOf(100)));

        // 策略 2 添加亏损交易
        calculator.addTrade(createTradeWithId(strategy2, false, BigDecimal.valueOf(50)));

        RollingMetrics metrics1 = calculator.calculateMetrics(strategy1);
        RollingMetrics metrics2 = calculator.calculateMetrics(strategy2);

        assertEquals(new BigDecimal("100.00"), metrics1.getRollingEV());
        assertEquals(new BigDecimal("-50.00"), metrics2.getRollingEV());
    }

    /**
     * 测试 hasSufficientData
     */
    @Test
    void testHasSufficientData() {
        // 添加 20 笔交易
        for (int i = 0; i < 20; i++) {
            calculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        }

        RollingMetrics metrics = calculator.calculateMetrics(STRATEGY_ID);

        assertTrue(metrics.hasSufficientData(20));
        assertTrue(metrics.hasSufficientData(15));
        assertFalse(metrics.hasSufficientData(25));
    }

    /**
     * 测试小窗口大小
     */
    @Test
    void testSmallWindowSize() {
        RollingEVCalculator smallCalculator = new RollingEVCalculator(10);

        // 添加 20 笔交易
        for (int i = 0; i < 20; i++) {
            smallCalculator.addTrade(createWinTrade(BigDecimal.valueOf(100)));
        }

        RollingMetrics metrics = smallCalculator.calculateMetrics(STRATEGY_ID);

        // 应该只保留最后 10 笔
        assertEquals(10, metrics.getSampleSize());
    }

    // ==================== 辅助方法 ====================

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
            SYMBOL,
            PositionSide.LONG,
            BigDecimal.valueOf(50000),  // entryPrice
            BigDecimal.valueOf(50100),  // exitPrice
            BigDecimal.valueOf(0.1),    // quantity
            actualPnl,                  // pnl (不含手续费)
            BigDecimal.ZERO,            // fee = 0 for clean EV calculation
            Instant.now().minusSeconds(3600),  // entryTime
            Instant.now(),                     // exitTime
            strategyId
        );
    }
}
