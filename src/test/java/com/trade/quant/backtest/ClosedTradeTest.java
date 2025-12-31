package com.trade.quant.backtest;

import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClosedTrade 单元测试
 */
class ClosedTradeTest {

    @Test
    void testWinningTrade() {
        Symbol symbol = Symbol.of("BTC-USDT");
        
        // 做多盈利交易: 入场 50000, 出场 52000, 数量 0.1
        // PnL = (52000 - 50000) * 0.1 = 200
        // Fee = 10
        // NetPnL = 200 - 10 = 190
        ClosedTrade trade = new ClosedTrade(
                "trade-001",
                symbol,
                PositionSide.LONG,
                BigDecimal.valueOf(50000),    // 入场价
                BigDecimal.valueOf(52000),    // 出场价
                BigDecimal.valueOf(0.1),      // 数量
                BigDecimal.valueOf(200),      // PnL
                BigDecimal.valueOf(10),       // 手续费
                Instant.now().minusSeconds(3600),
                Instant.now(),
                "TestStrategy"
        );

        assertTrue(trade.isWin(), "盈利交易应该返回 isWin=true");
        assertFalse(trade.isLoss(), "盈利交易应该返回 isLoss=false");
        assertEquals(0, BigDecimal.valueOf(190).compareTo(trade.getNetPnl()));
    }

    @Test
    void testLosingTrade() {
        Symbol symbol = Symbol.of("BTC-USDT");
        
        // 做多亏损交易: 入场 50000, 出场 48000, 数量 0.1
        // PnL = (48000 - 50000) * 0.1 = -200
        // Fee = 10
        // NetPnL = -200 - 10 = -210
        ClosedTrade trade = new ClosedTrade(
                "trade-002",
                symbol,
                PositionSide.LONG,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(48000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(-200),
                BigDecimal.valueOf(10),
                Instant.now().minusSeconds(3600),
                Instant.now(),
                "TestStrategy"
        );

        assertFalse(trade.isWin(), "亏损交易应该返回 isWin=false");
        assertTrue(trade.isLoss(), "亏损交易应该返回 isLoss=true");
        assertEquals(0, BigDecimal.valueOf(-210).compareTo(trade.getNetPnl()));
    }

    @Test
    void testShortTrade() {
        Symbol symbol = Symbol.of("ETH-USDT");
        
        // 做空盈利交易: 入场 3000, 出场 2800, 数量 1
        // PnL = (3000 - 2800) * 1 = 200
        // Fee = 5
        // NetPnL = 200 - 5 = 195
        ClosedTrade trade = new ClosedTrade(
                "trade-003",
                symbol,
                PositionSide.SHORT,
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(2800),
                BigDecimal.valueOf(1),
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(5),
                Instant.now().minusSeconds(7200),
                Instant.now(),
                "ShortStrategy"
        );

        assertTrue(trade.isWin());
        assertEquals(0, BigDecimal.valueOf(195).compareTo(trade.getNetPnl()));
        assertEquals(PositionSide.SHORT, trade.getSide());
    }

    @Test
    void testReturnPercent() {
        Symbol symbol = Symbol.of("BTC-USDT");
        
        // 入场价值 = 50000 * 0.1 = 5000
        // NetPnL = 190
        // 收益率 = 190 / 5000 * 100 = 3.8%
        ClosedTrade trade = new ClosedTrade(
                "trade-004",
                symbol,
                PositionSide.LONG,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(52000),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(10),
                Instant.now().minusSeconds(3600),
                Instant.now(),
                "TestStrategy"
        );

        BigDecimal returnPercent = trade.getReturnPercent();
        // 收益率应该约为 3.8%
        assertTrue(returnPercent.compareTo(BigDecimal.valueOf(3.5)) > 0);
        assertTrue(returnPercent.compareTo(BigDecimal.valueOf(4.5)) < 0);
    }

    @Test
    void testTradeInfo() {
        Symbol symbol = Symbol.of("BTC-USDT");
        Instant entryTime = Instant.now().minusSeconds(3600);
        Instant exitTime = Instant.now();
        
        ClosedTrade trade = new ClosedTrade(
                "trade-005",
                symbol,
                PositionSide.LONG,
                BigDecimal.valueOf(50000),
                BigDecimal.valueOf(51000),
                BigDecimal.valueOf(0.05),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(5),
                entryTime,
                exitTime,
                "InfoStrategy"
        );

        assertEquals("trade-005", trade.getTradeId());
        assertEquals(symbol, trade.getSymbol());
        assertEquals(PositionSide.LONG, trade.getSide());
        assertEquals(0, BigDecimal.valueOf(50000).compareTo(trade.getEntryPrice()));
        assertEquals(0, BigDecimal.valueOf(51000).compareTo(trade.getExitPrice()));
        assertEquals(0, BigDecimal.valueOf(0.05).compareTo(trade.getQuantity()));
        assertEquals(entryTime, trade.getEntryTime());
        assertEquals(exitTime, trade.getExitTime());
        assertEquals("InfoStrategy", trade.getStrategyId());
    }
}
