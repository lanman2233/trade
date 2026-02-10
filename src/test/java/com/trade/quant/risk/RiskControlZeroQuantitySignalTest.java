package com.trade.quant.risk;

import com.trade.quant.core.AccountInfo;
import com.trade.quant.core.Order;
import com.trade.quant.core.Position;
import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskControlZeroQuantitySignalTest {

    private RiskControl riskControl;
    private Symbol symbol;

    @BeforeEach
    void setUp() {
        RiskConfig config = RiskConfig.builder()
                .riskPerTrade(new BigDecimal("0.01"))
                .maxPositionRatio(new BigDecimal("0.20"))
                .maxStopLossPercent(new BigDecimal("50"))
                .maxConsecutiveLosses(3)
                .maxDrawdownPercent(new BigDecimal("30"))
                .leverage(new BigDecimal("2"))
                .build();
        config.setMarginBuffer(BigDecimal.ONE);

        AccountInfo accountInfo = new AccountInfo(
                new BigDecimal("10000"),
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        riskControl = new RiskControl(config, accountInfo);
        symbol = Symbol.of("BTC-USDT");
    }

    @Test
    void entrySignalWithZeroQuantityShouldCreateSizedOrder() {
        Signal signal = new Signal(
                "TEST-STRATEGY",
                symbol,
                SignalType.ENTRY_SHORT,
                Side.SELL,
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                new BigDecimal("10100"),
                BigDecimal.ZERO,
                "entry-zero-qty"
        );

        Order order = riskControl.validateAndCreateOrder(signal, List.of());

        assertNotNull(order);
        assertTrue(order.getQuantity().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(Side.SELL, order.getSide());
    }

    @Test
    void exitSignalWithZeroQuantityShouldCloseTrackedPositionQuantity() {
        Position existingLong = new Position(
                symbol,
                PositionSide.LONG,
                new BigDecimal("10000"),
                new BigDecimal("0.050"),
                new BigDecimal("9900"),
                BigDecimal.ONE
        );

        Signal signal = new Signal(
                "TEST-STRATEGY",
                symbol,
                SignalType.EXIT_LONG,
                Side.SELL,
                new BigDecimal("10050"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "exit-zero-qty"
        );

        Order order = riskControl.validateAndCreateOrder(signal, List.of(existingLong));

        assertNotNull(order);
        assertTrue(order.isReduceOnly());
        assertEquals(new BigDecimal("0.050"), order.getQuantity());
    }
}
