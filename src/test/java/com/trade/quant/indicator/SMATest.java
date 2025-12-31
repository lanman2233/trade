package com.trade.quant.indicator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SMA 指标单元测试
 */
class SMATest {

    private List<BigDecimal> testPrices;

    @BeforeEach
    void setUp() {
        // 测试价格序列: 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20
        testPrices = Arrays.asList(
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(11),
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(13),
                BigDecimal.valueOf(14),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(16),
                BigDecimal.valueOf(17),
                BigDecimal.valueOf(18),
                BigDecimal.valueOf(19),
                BigDecimal.valueOf(20)
        );
    }

    @Test
    void testSMACalculation_Period5() {
        SMA sma = new SMA(5);
        List<BigDecimal> result = sma.calculate(testPrices);

        // 期望 SMA5 序列:
        // (10+11+12+13+14)/5 = 12
        // (11+12+13+14+15)/5 = 13
        // ... 以此类推
        assertEquals(7, result.size()); // 11 - 5 + 1 = 7 个结果

        // 第一个 SMA5 = (10+11+12+13+14)/5 = 12
        assertEquals(0, BigDecimal.valueOf(12).compareTo(result.get(0)));

        // 最后一个 SMA5 = (16+17+18+19+20)/5 = 18
        assertEquals(0, BigDecimal.valueOf(18).compareTo(result.get(6)));
    }

    @Test
    void testSMALatest() {
        SMA sma = new SMA(3);
        BigDecimal latest = sma.latest(testPrices);

        // 最后3个价格: 18, 19, 20
        // SMA3 = (18+19+20)/3 = 19
        assertEquals(0, BigDecimal.valueOf(19).compareTo(latest));
    }

    @Test
    void testSMAWithInsufficientData() {
        SMA sma = new SMA(5);
        List<BigDecimal> shortPrices = Arrays.asList(
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(11),
                BigDecimal.valueOf(12)
        );

        assertThrows(IllegalArgumentException.class, () -> sma.calculate(shortPrices));
    }

    @Test
    void testSMAInvalidPeriod() {
        assertThrows(IllegalArgumentException.class, () -> new SMA(0));
        assertThrows(IllegalArgumentException.class, () -> new SMA(-1));
    }

    @Test
    void testSMAName() {
        SMA sma = new SMA(20);
        assertEquals("SMA-20", sma.getName());
    }
}
