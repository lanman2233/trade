package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 实时行情快照
 */
public class Ticker {
    private final Symbol symbol;
    private final BigDecimal bidPrice;       // 最优买一价
    private final BigDecimal askPrice;       // 最优卖一价
    private final BigDecimal lastPrice;      // 最新成交价
    private final BigDecimal volume24h;      // 24h成交量
    private final BigDecimal high24h;        // 24h最高价
    private final BigDecimal low24h;         // 24h最低价
    private final Instant timestamp;         // 时间戳

    public Ticker(Symbol symbol, BigDecimal bidPrice, BigDecimal askPrice,
                 BigDecimal lastPrice, BigDecimal volume24h,
                 BigDecimal high24h, BigDecimal low24h, Instant timestamp) {
        this.symbol = symbol;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.lastPrice = lastPrice;
        this.volume24h = volume24h;
        this.high24h = high24h;
        this.low24h = low24h;
        this.timestamp = timestamp;
    }

    public Symbol getSymbol() { return symbol; }
    public BigDecimal getBidPrice() { return bidPrice; }
    public BigDecimal getAskPrice() { return askPrice; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getVolume24h() { return volume24h; }
    public BigDecimal getHigh24h() { return high24h; }
    public BigDecimal getLow24h() { return low24h; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * 获取买卖价差（百分比）
     */
    public BigDecimal getSpreadPercent() {
        if (askPrice == null || bidPrice == null || bidPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return Decimal.percentChange(bidPrice, askPrice);
    }

    /**
     * 获取中间价
     */
    public BigDecimal getMidPrice() {
        if (bidPrice == null || askPrice == null) {
            return lastPrice;
        }
        return bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);
    }
}
