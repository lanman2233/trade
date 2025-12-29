package com.trade.quant.risk;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 风控状态
 */
public class RiskStatus {

    private final boolean tradingEnabled;
    private final BigDecimal maxDrawdown;
    private final Map<String, Integer> consecutiveLosses;
    private final Map<String, BigDecimal> dailyLoss;

    public RiskStatus(boolean tradingEnabled, BigDecimal maxDrawdown,
                     Map<String, Integer> consecutiveLosses, Map<String, BigDecimal> dailyLoss) {
        this.tradingEnabled = tradingEnabled;
        this.maxDrawdown = maxDrawdown;
        this.consecutiveLosses = consecutiveLosses;
        this.dailyLoss = dailyLoss;
    }

    public boolean isTradingEnabled() {
        return tradingEnabled;
    }

    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    public Map<String, Integer> getConsecutiveLosses() {
        return consecutiveLosses;
    }

    public Map<String, BigDecimal> getDailyLoss() {
        return dailyLoss;
    }

    @Override
    public String toString() {
        return String.format("RiskStatus{trading=%s, maxDrawdown=%.2f%%, consecutiveLosses=%s}",
                tradingEnabled, maxDrawdown, consecutiveLosses);
    }
}
