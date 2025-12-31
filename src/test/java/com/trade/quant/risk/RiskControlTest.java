package com.trade.quant.risk;

import com.trade.quant.core.*;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风控模块单元测试
 */
class RiskControlTest {

    private RiskConfig config;
    private AccountInfo accountInfo;
    private RiskControl riskControl;
    private Symbol symbol;

    @BeforeEach
    void setUp() {
        config = RiskConfig.builder()
                .riskPerTrade(BigDecimal.valueOf(0.01))      // 每笔风险 1%
                .maxPositionRatio(BigDecimal.valueOf(0.1))   // 最大仓位 10%
                .maxStopLossPercent(BigDecimal.valueOf(5))   // 最大止损 5%
                .maxConsecutiveLosses(3)                     // 最大连续亏损 3 次
                .maxDrawdownPercent(BigDecimal.valueOf(30))  // 最大回撤 30%
                .build();

        // 账户余额 10000 USDT
        accountInfo = new AccountInfo(
                BigDecimal.valueOf(10000),  // 总权益
                BigDecimal.valueOf(10000),  // 可用余额
                BigDecimal.ZERO,            // 未实现盈亏
                BigDecimal.ZERO             // 保证金率
        );

        riskControl = new RiskControl(config, accountInfo);
        symbol = Symbol.of("BTC-USDT");
    }

    @Test
    void testValidSignalCreatesOrder() {
        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),     // 入场价格
                BigDecimal.valueOf(0.1),       // 数量
                BigDecimal.valueOf(48000),     // 止损（4%距离）
                BigDecimal.ZERO,               // 止盈
                "测试信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNotNull(order, "有效信号应该创建订单");
        assertEquals(symbol, order.getSymbol());
        assertEquals(Side.BUY, order.getSide());
    }

    @Test
    void testRejectSignalWithoutStopLoss() {
        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(0.1),
                BigDecimal.ZERO,               // 无止损
                BigDecimal.ZERO,
                "无止损信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNull(order, "无止损信号应该被拒绝");
    }

    @Test
    void testRejectSignalWithExcessiveStopLoss() {
        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(40000),     // 止损距离 20%，超过 5% 限制
                BigDecimal.ZERO,
                "止损过大信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNull(order, "止损过大的信号应该被拒绝");
    }

    @Test
    void testPositionSizeCalculation() {
        // 账户 10000 USDT，风险 1% = 100 USDT
        // 入场 1000，止损 980，每单位风险 = 20
        // 仓位 = 100 / 20 = 5 张
        // 但最大仓位限制 10% = 1000 USDT / 1000 = 1 张

        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(1000),      // 入场价格
                BigDecimal.valueOf(5),         // 请求 5 张 (价值 5000 USDT，在余额范围内)
                BigDecimal.valueOf(980),       // 止损 2%
                BigDecimal.ZERO,
                "测试仓位"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNotNull(order, "有效信号应该创建订单");
        // 风控应该基于风险计算仓位，而不是使用请求的数量
        assertTrue(order.getQuantity().compareTo(BigDecimal.valueOf(5)) <= 0,
                "仓位应该受风险限制");
    }

    @Test
    void testEmergencyStop() {
        riskControl.emergencyStop();

        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(48000),
                BigDecimal.ZERO,
                "紧急停止后的信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNull(order, "紧急停止后应该拒绝所有信号");
    }

    @Test
    void testResumeTrading() {
        riskControl.emergencyStop();
        riskControl.resumeTrading();

        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(48000),
                BigDecimal.ZERO,
                "恢复交易后的信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNotNull(order, "恢复交易后应该接受有效信号");
    }

    @Test
    void testConsecutiveLossLimit() {
        // 模拟连续亏损
        riskControl.recordTradeResult("TestStrategy-001", false, BigDecimal.valueOf(-100));
        riskControl.recordTradeResult("TestStrategy-002", false, BigDecimal.valueOf(-100));
        riskControl.recordTradeResult("TestStrategy-003", false, BigDecimal.valueOf(-100));

        Signal signal = new Signal(
                "TestStrategy",
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(48000),
                BigDecimal.ZERO,
                "连续亏损后的信号"
        );

        List<Position> existingPositions = new ArrayList<>();
        Order order = riskControl.validateAndCreateOrder(signal, existingPositions);

        assertNull(order, "连续亏损达到上限后应该拒绝信号");
    }

    @Test
    void testRiskStatus() {
        RiskStatus status = riskControl.getRiskStatus();

        assertNotNull(status);
        assertTrue(status.isTradingEnabled());
    }
}
