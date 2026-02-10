package com.trade.quant.risk;

import com.trade.quant.core.OrderType;
import java.math.BigDecimal;

/**
 * 风控配置
 */
public class RiskConfig {

    private boolean tradingEnabled = true;
    private BigDecimal riskPerTrade = BigDecimal.valueOf(0.01);        // 每笔交易风险比例
    private BigDecimal maxPositionRatio = BigDecimal.valueOf(0.1);     // 最大仓位比例
    private BigDecimal maxStopLossPercent = BigDecimal.valueOf(5);     // 最大止损百分比
    private int maxConsecutiveLosses = 3;                              // 最大连续亏损次数
    private BigDecimal maxDrawdownPercent = BigDecimal.valueOf(30);    // 最大回撤百分比
    private int maxPositionsPerSymbol = 1;                             // 每个交易对最大持仓数
    private BigDecimal marginBuffer = BigDecimal.valueOf(1.2);         // 保证金缓冲比例
    private BigDecimal leverage = BigDecimal.ONE;                      // 杠杆倍数（期货）
    private OrderType defaultOrderType = OrderType.MARKET;             // 默认订单类型

    public boolean isTradingEnabled() {
        return tradingEnabled;
    }

    public void setTradingEnabled(boolean tradingEnabled) {
        this.tradingEnabled = tradingEnabled;
    }

    public BigDecimal getRiskPerTrade() {
        return riskPerTrade;
    }

    public void setRiskPerTrade(BigDecimal riskPerTrade) {
        if (riskPerTrade.compareTo(BigDecimal.valueOf(0.02)) > 0) {
            throw new IllegalArgumentException("每笔交易风险不能超过2%");
        }
        if (riskPerTrade.compareTo(BigDecimal.valueOf(0.005)) < 0) {
            throw new IllegalArgumentException("每笔交易风险不能低于0.5%");
        }
        this.riskPerTrade = riskPerTrade;
    }

    public BigDecimal getMaxPositionRatio() {
        return maxPositionRatio;
    }

    public void setMaxPositionRatio(BigDecimal maxPositionRatio) {
        this.maxPositionRatio = maxPositionRatio;
    }

    public BigDecimal getMaxStopLossPercent() {
        return maxStopLossPercent;
    }

    public void setMaxStopLossPercent(BigDecimal maxStopLossPercent) {
        this.maxStopLossPercent = maxStopLossPercent;
    }

    public int getMaxConsecutiveLosses() {
        return maxConsecutiveLosses;
    }

    public void setMaxConsecutiveLosses(int maxConsecutiveLosses) {
        this.maxConsecutiveLosses = maxConsecutiveLosses;
    }

    public BigDecimal getMaxDrawdownPercent() {
        return maxDrawdownPercent;
    }

    public void setMaxDrawdownPercent(BigDecimal maxDrawdownPercent) {
        if (maxDrawdownPercent.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw new IllegalArgumentException("最大回撤不能超过50%");
        }
        this.maxDrawdownPercent = maxDrawdownPercent;
    }

    public int getMaxPositionsPerSymbol() {
        return maxPositionsPerSymbol;
    }

    public void setMaxPositionsPerSymbol(int maxPositionsPerSymbol) {
        this.maxPositionsPerSymbol = maxPositionsPerSymbol;
    }

    public BigDecimal getMarginBuffer() {
        return marginBuffer;
    }

    public void setMarginBuffer(BigDecimal marginBuffer) {
        this.marginBuffer = marginBuffer;
    }

    public BigDecimal getLeverage() {
        return leverage;
    }

    public void setLeverage(BigDecimal leverage) {
        if (leverage == null || leverage.compareTo(BigDecimal.ZERO) <= 0) {
            this.leverage = BigDecimal.ONE;
        } else {
            this.leverage = leverage;
        }
    }

    public OrderType getDefaultOrderType() {
        return defaultOrderType;
    }

    public void setDefaultOrderType(OrderType defaultOrderType) {
        this.defaultOrderType = defaultOrderType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final RiskConfig config = new RiskConfig();

        public Builder riskPerTrade(BigDecimal value) {
            config.setRiskPerTrade(value);
            return this;
        }

        public Builder maxPositionRatio(BigDecimal value) {
            config.setMaxPositionRatio(value);
            return this;
        }

        public Builder maxStopLossPercent(BigDecimal value) {
            config.setMaxStopLossPercent(value);
            return this;
        }

        public Builder maxConsecutiveLosses(int value) {
            config.setMaxConsecutiveLosses(value);
            return this;
        }

        public Builder maxDrawdownPercent(BigDecimal value) {
            config.setMaxDrawdownPercent(value);
            return this;
        }

        public Builder defaultOrderType(OrderType value) {
            config.setDefaultOrderType(value);
            return this;
        }

        public Builder leverage(BigDecimal value) {
            config.setLeverage(value);
            return this;
        }

        public RiskConfig build() {
            return config;
        }
    }
}
