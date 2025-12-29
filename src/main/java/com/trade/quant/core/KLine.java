package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * K线数据
 */
public class KLine {
    private final Symbol symbol;
    private final Interval interval;         // 周期
    private final Instant openTime;          // 开盘时间
    private final Instant closeTime;         // 收盘时间
    private final BigDecimal open;           // 开盘价
    private final BigDecimal high;           // 最高价
    private final BigDecimal low;            // 最低价
    private final BigDecimal close;          // 收盘价
    private final BigDecimal volume;         // 成交量
    private final BigDecimal quoteVolume;    // 成交额
    private final long trades;               // 成交笔数

    public KLine(Symbol symbol, Interval interval, Instant openTime, Instant closeTime,
                BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                BigDecimal volume, BigDecimal quoteVolume, long trades) {
        this.symbol = symbol;
        this.interval = interval;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.quoteVolume = quoteVolume;
        this.trades = trades;
    }

    public Symbol getSymbol() { return symbol; }
    public Interval getInterval() { return interval; }
    public Instant getOpenTime() { return openTime; }
    public Instant getCloseTime() { return closeTime; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public BigDecimal getVolume() { return volume; }
    public BigDecimal getQuoteVolume() { return quoteVolume; }
    public long getTrades() { return trades; }

    /**
     * K线实体长度（绝对值）
     */
    public BigDecimal getBodySize() {
        return close.subtract(open).abs();
    }

    /**
     * 上影线长度
     */
    public BigDecimal getUpperShadow() {
        return high.subtract(max(open, close));
    }

    /**
     * 下影线长度
     */
    public BigDecimal getLowerShadow() {
        return min(open, close).subtract(low);
    }

    /**
     * 是否为阳线
     */
    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    /**
     * 是否为阴线
     */
    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }

    /**
     * 涨跌幅（百分比）
     */
    public BigDecimal getChangePercent() {
        return Decimal.percentChange(open, close);
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    @Override
    public String toString() {
        return String.format("KLine{symbol=%s, interval=%s, time=%s, OHLC=[%s,%s,%s,%s], vol=%s}",
                symbol, interval, openTime, open, high, low, close, volume);
    }
}
