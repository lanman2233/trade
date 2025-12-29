package com.trade.quant.core;

import java.math.BigDecimal;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BigDecimal 工具类
 * 所有金额、价格、数量计算必须使用此类，禁止 double
 */
public final class Decimal {

    private Decimal() {}

    /**
     * 默认精度：价格保留8位小数
     */
    private static final int PRICE_SCALE = 8;

    /**
     * 默认精度：数量保留3位小数
     */
    private static final int QUANTITY_SCALE = 3;

    /**
     * 默认精度：百分比保留2位小数
     */
    private static final int PERCENT_SCALE = 2;

    public static BigDecimal of(String value) {
        return new BigDecimal(value);
    }

    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO;
    }

    public static BigDecimal one() {
        return BigDecimal.ONE;
    }

    /**
     * 价格格式化（8位小数）
     */
    public static BigDecimal scalePrice(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 数量格式化（3位小数）
     */
    public static BigDecimal scaleQuantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.DOWN);
    }

    /**
     * 百分比格式化（2位小数）
     */
    public static BigDecimal scalePercent(BigDecimal value) {
        return value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 安全除法，避免除零
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return dividend.divide(divisor, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算百分比变化
     */
    public static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return to.subtract(from)
                .divide(from, PERCENT_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 判断是否为正值
     */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 判断是否为负值
     */
    public static boolean isNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 判断是否为零
     */
    public static boolean isZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) == 0;
    }
}
