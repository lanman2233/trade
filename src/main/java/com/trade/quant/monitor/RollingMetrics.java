package com.trade.quant.monitor;

import java.math.BigDecimal;

/**
 * 滚动 Expected Value (EV) 指标
 * 不可变值对象，包含策略性能的滚动统计信息
 */
public class RollingMetrics {

    private final String strategyId;
    private final int sampleSize;
    private final BigDecimal rollingEV;
    private final BigDecimal winRate;
    private final BigDecimal avgWin;
    private final BigDecimal avgLoss;
    private final int consecutiveLosses;

    public RollingMetrics(String strategyId, int sampleSize, BigDecimal rollingEV,
                         BigDecimal winRate, BigDecimal avgWin, BigDecimal avgLoss,
                         int consecutiveLosses) {
        this.strategyId = strategyId;
        this.sampleSize = sampleSize;
        this.rollingEV = rollingEV;
        this.winRate = winRate;
        this.avgWin = avgWin;
        this.avgLoss = avgLoss;
        this.consecutiveLosses = consecutiveLosses;
    }

    /**
     * 创建空指标（无足够数据）
     */
    public static RollingMetrics empty() {
        return new RollingMetrics("", 0, BigDecimal.ZERO,
                                  BigDecimal.ZERO, BigDecimal.ZERO,
                                  BigDecimal.ZERO, 0);
    }

    /**
     * 判断是否有足够样本
     */
    public boolean hasSufficientData(int minSampleSize) {
        return sampleSize >= minSampleSize;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public BigDecimal getRollingEV() {
        return rollingEV;
    }

    public BigDecimal getWinRate() {
        return winRate;
    }

    public BigDecimal getAvgWin() {
        return avgWin;
    }

    public BigDecimal getAvgLoss() {
        return avgLoss;
    }

    public int getConsecutiveLosses() {
        return consecutiveLosses;
    }

    @Override
    public String toString() {
        return String.format(
            "RollingMetrics{strategyId='%s', sampleSize=%d, rollingEV=%s, winRate=%s, avgWin=%s, avgLoss=%s, consecutiveLosses=%d}",
            strategyId, sampleSize, rollingEV, winRate, avgWin, avgLoss, consecutiveLosses
        );
    }
}
