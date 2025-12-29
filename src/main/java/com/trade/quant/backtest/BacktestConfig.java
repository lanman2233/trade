package com.trade.quant.backtest;

import com.trade.quant.core.Interval;
import com.trade.quant.core.Symbol;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 回测配置
 */
public class BacktestConfig {

    private final Symbol symbol;
    private final Interval interval;
    private final Instant startTime;
    private final Instant endTime;
    private final BigDecimal initialCapital;
    private final BigDecimal makerFee;
    private final BigDecimal takerFee;
    private final BigDecimal slippage;
    private final BigDecimal leverage;

    private BacktestConfig(Builder builder) {
        this.symbol = builder.symbol;
        this.interval = builder.interval;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.initialCapital = builder.initialCapital;
        this.makerFee = builder.makerFee;
        this.takerFee = builder.takerFee;
        this.slippage = builder.slippage;
        this.leverage = builder.leverage;
    }

    public Symbol getSymbol() { return symbol; }
    public Interval getInterval() { return interval; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public BigDecimal getInitialCapital() { return initialCapital; }
    public BigDecimal getMakerFee() { return makerFee; }
    public BigDecimal getTakerFee() { return takerFee; }
    public BigDecimal getSlippage() { return slippage; }
    public BigDecimal getLeverage() { return leverage; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Symbol symbol;
        private Interval interval;
        private Instant startTime;
        private Instant endTime;
        private BigDecimal initialCapital = BigDecimal.valueOf(10000); // 默认10000 USDT
        private BigDecimal makerFee = BigDecimal.valueOf(0.0002);      // 0.02% maker费率
        private BigDecimal takerFee = BigDecimal.valueOf(0.0004);      // 0.04% taker费率
        private BigDecimal slippage = BigDecimal.valueOf(0.0005);      // 0.05% 滑点
        private BigDecimal leverage = BigDecimal.ONE;                  // 默认无杠杆

        public Builder symbol(Symbol symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder interval(Interval interval) {
            this.interval = interval;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder initialCapital(BigDecimal capital) {
            this.initialCapital = capital;
            return this;
        }

        public Builder makerFee(BigDecimal fee) {
            this.makerFee = fee;
            return this;
        }

        public Builder takerFee(BigDecimal fee) {
            this.takerFee = fee;
            return this;
        }

        public Builder slippage(BigDecimal slippage) {
            this.slippage = slippage;
            return this;
        }

        public Builder leverage(BigDecimal leverage) {
            this.leverage = leverage;
            return this;
        }

        public BacktestConfig build() {
            if (symbol == null || interval == null || startTime == null || endTime == null) {
                throw new IllegalStateException("symbol, interval, startTime, endTime 必须设置");
            }
            if (startTime.isAfter(endTime)) {
                throw new IllegalArgumentException("startTime 不能晚于 endTime");
            }
            return new BacktestConfig(this);
        }
    }
}
