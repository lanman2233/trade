package com.trade.quant.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decimal 工具类单元测试
 */
class DecimalTest {

    @Test
    void testOf_String() {
        BigDecimal value = Decimal.of("123.45678901");
        assertEquals(0, new BigDecimal("123.45678901").compareTo(value));
    }

    @Test
    void testOf_Double() {
        BigDecimal value = Decimal.of(123.45);
        assertEquals(0, BigDecimal.valueOf(123.45).compareTo(value));
    }

    @Test
    void testZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(Decimal.zero()));
    }

    @Test
    void testOne() {
        assertEquals(0, BigDecimal.ONE.compareTo(Decimal.one()));
    }

    @Test
    void testScalePrice() {
        BigDecimal price = new BigDecimal("123.456789012345");
        BigDecimal scaled = Decimal.scalePrice(price);
        
        // 价格应该保留8位小数
        assertEquals(8, scaled.scale());
    }

    @Test
    void testScaleQuantity() {
        BigDecimal quantity = new BigDecimal("123.456789");
        BigDecimal scaled = Decimal.scaleQuantity(quantity);
        
        // 数量应该保留3位小数，向下取整
        assertEquals(3, scaled.scale());
        assertEquals(0, new BigDecimal("123.456").compareTo(scaled));
    }

    @Test
    void testScalePercent() {
        BigDecimal percent = new BigDecimal("12.3456");
        BigDecimal scaled = Decimal.scalePercent(percent);
        
        // 百分比应该保留2位小数
        assertEquals(2, scaled.scale());
        assertEquals(0, new BigDecimal("12.35").compareTo(scaled));
    }

    @Test
    void testDivide_Normal() {
        BigDecimal dividend = new BigDecimal("100");
        BigDecimal divisor = new BigDecimal("3");
        BigDecimal result = Decimal.divide(dividend, divisor);
        
        // 100 / 3 ≈ 33.33333333
        assertTrue(result.compareTo(new BigDecimal("33.33")) > 0);
        assertTrue(result.compareTo(new BigDecimal("33.34")) < 0);
    }

    @Test
    void testDivide_ByZero() {
        BigDecimal dividend = new BigDecimal("100");
        BigDecimal divisor = BigDecimal.ZERO;
        BigDecimal result = Decimal.divide(dividend, divisor);
        
        // 除零应该返回0，不是抛出异常
        assertEquals(0, BigDecimal.ZERO.compareTo(result));
    }

    @Test
    void testPercentChange() {
        BigDecimal from = new BigDecimal("100");
        BigDecimal to = new BigDecimal("110");
        BigDecimal change = Decimal.percentChange(from, to);
        
        // (110 - 100) / 100 * 100 = 10%
        assertEquals(0, new BigDecimal("10.00").compareTo(change));
    }

    @Test
    void testPercentChange_FromZero() {
        BigDecimal from = BigDecimal.ZERO;
        BigDecimal to = new BigDecimal("100");
        BigDecimal change = Decimal.percentChange(from, to);
        
        // 从0开始应该返回0
        assertEquals(0, BigDecimal.ZERO.compareTo(change));
    }

    @Test
    void testPercentChange_Negative() {
        BigDecimal from = new BigDecimal("100");
        BigDecimal to = new BigDecimal("90");
        BigDecimal change = Decimal.percentChange(from, to);
        
        // (90 - 100) / 100 * 100 = -10%
        assertEquals(0, new BigDecimal("-10.00").compareTo(change));
    }

    @Test
    void testIsPositive() {
        assertTrue(Decimal.isPositive(new BigDecimal("1")));
        assertTrue(Decimal.isPositive(new BigDecimal("0.0001")));
        assertFalse(Decimal.isPositive(BigDecimal.ZERO));
        assertFalse(Decimal.isPositive(new BigDecimal("-1")));
        assertFalse(Decimal.isPositive(null));
    }

    @Test
    void testIsNegative() {
        assertTrue(Decimal.isNegative(new BigDecimal("-1")));
        assertTrue(Decimal.isNegative(new BigDecimal("-0.0001")));
        assertFalse(Decimal.isNegative(BigDecimal.ZERO));
        assertFalse(Decimal.isNegative(new BigDecimal("1")));
        assertFalse(Decimal.isNegative(null));
    }

    @Test
    void testIsZero() {
        assertTrue(Decimal.isZero(BigDecimal.ZERO));
        assertTrue(Decimal.isZero(new BigDecimal("0.0")));
        assertTrue(Decimal.isZero(new BigDecimal("0.00000000")));
        assertFalse(Decimal.isZero(new BigDecimal("1")));
        assertFalse(Decimal.isZero(new BigDecimal("-1")));
        assertFalse(Decimal.isZero(null));
    }
}
