package com.trade.quant.core;

/**
 * K线周期
 * 仅支持 1m 和 5m，禁止高频交易
 */
public enum Interval {
    ONE_MINUTE("1m", 1),
    FIVE_MINUTES("5m", 5),
    FIFTEEN_MINUTES("15m", 15),
    ONE_HOUR("1h", 60);

    private final String code;
    private final int minutes;

    Interval(String code, int minutes) {
        this.code = code;
        this.minutes = minutes;
    }

    public String getCode() {
        return code;
    }

    public int getMinutes() {
        return minutes;
    }

    public static Interval fromCode(String code) {
        for (Interval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("不支持的K线周期: " + code);
    }
}
