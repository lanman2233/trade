package com.trade.quant.core;

/**
 * 订单方向
 */
public enum Side {
    BUY("做多", "买入开多"),
    SELL("做空", "卖出开空");

    private final String chineseName;
    private final String description;

    Side(String chineseName, String description) {
        this.chineseName = chineseName;
        this.description = description;
    }

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    public String getChineseName() {
        return chineseName;
    }

    public String getDescription() {
        return description;
    }
}
