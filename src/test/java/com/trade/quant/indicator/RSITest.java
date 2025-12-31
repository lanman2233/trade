package com.trade.quant.indicator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RSI 指标单元测试
 */
class RSITest {

    @Test
    void testRSICalculation() {
        // 创建一个上涨趋势的价格序列
        List<BigDecimal> uptrend = Arrays.asList(
                BigDecimal.valueOf(44),
                BigDecimal.valueOf(44.34),
                BigDecimal.valueOf(44.09),
                BigDecimal.valueOf(43.61),
                BigDecimal.valueOf(44.33),
                BigDecimal.valueOf(44.83),
                BigDecimal.valueOf(45.10),
                BigDecimal.valueOf(45.42),
                BigDecimal.valueOf(45.84),
                BigDecimal.valueOf(46.08),
                BigDecimal.valueOf(45.89),
                BigDecimal.valueOf(46.03),
                BigDecimal.valueOf(45.61),
                BigDecimal.valueOf(46.28),
                BigDecimal.valueOf(46.28),
                BigDecimal.valueOf(46.00)
        );

        RSI rsi = new RSI(14);
        List<BigDecimal> result = rsi.calculate(uptrend);

        // RSI 应该有 2 个值 (16 - 14 - 1 + 1 = 2)
        assertEquals(2, result.size());

        // 在上涨趋势中 RSI 应该高于 50
        assertTrue(result.get(0).compareTo(BigDecimal.valueOf(50)) > 0);
    }

    @Test
    void testRSIOverbought() {
        // 创建一个强上涨趋势
        List<BigDecimal> strongUptrend = Arrays.asList(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(102),
                BigDecimal.valueOf(104),
                BigDecimal.valueOf(106),
                BigDecimal.valueOf(108),
                BigDecimal.valueOf(110),
                BigDecimal.valueOf(112),
                BigDecimal.valueOf(114),
                BigDecimal.valueOf(116),
                BigDecimal.valueOf(118),
                BigDecimal.valueOf(120),
                BigDecimal.valueOf(122),
                BigDecimal.valueOf(124),
                BigDecimal.valueOf(126),
                BigDecimal.valueOf(128),
                BigDecimal.valueOf(130)
        );

        RSI rsi = new RSI(14);
        boolean overbought = rsi.isOverbought(strongUptrend, BigDecimal.valueOf(70));

        // 强上涨趋势 RSI 应该超过70（超买）
        assertTrue(overbought);
    }

    @Test
    void testRSIOversold() {
        // 创建一个强下跌趋势
        List<BigDecimal> strongDowntrend = Arrays.asList(
                BigDecimal.valueOf(130),
                BigDecimal.valueOf(128),
                BigDecimal.valueOf(126),
                BigDecimal.valueOf(124),
                BigDecimal.valueOf(122),
                BigDecimal.valueOf(120),
                BigDecimal.valueOf(118),
                BigDecimal.valueOf(116),
                BigDecimal.valueOf(114),
                BigDecimal.valueOf(112),
                BigDecimal.valueOf(110),
                BigDecimal.valueOf(108),
                BigDecimal.valueOf(106),
                BigDecimal.valueOf(104),
                BigDecimal.valueOf(102),
                BigDecimal.valueOf(100)
        );

        RSI rsi = new RSI(14);
        boolean oversold = rsi.isOversold(strongDowntrend, BigDecimal.valueOf(30));

        // 强下跌趋势 RSI 应该低于30（超卖）
        assertTrue(oversold);
    }

    @Test
    void testRSIWithInsufficientData() {
        RSI rsi = new RSI(14);
        List<BigDecimal> shortPrices = Arrays.asList(
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(11),
                BigDecimal.valueOf(12)
        );

        assertThrows(IllegalArgumentException.class, () -> rsi.calculate(shortPrices));
    }

    @Test
    void testRSIInvalidPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new RSI(0));
        assertThrows(IllegalArgumentException.class, () -> new RSI(-1));
    }

    @Test
    void testRSIName() {
        RSI rsi = new RSI(14);
        assertEquals("RSI-14", rsi.getName());
    }
}
