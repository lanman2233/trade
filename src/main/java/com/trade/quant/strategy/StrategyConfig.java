package com.trade.quant.strategy;

import java.math.BigDecimal;

/**
 * 策略配置
 */
public class StrategyConfig {

    private BigDecimal riskPerTrade = BigDecimal.valueOf(0.01);  // 每笔交易风险（1%）
    private BigDecimal maxPositionSize = BigDecimal.valueOf(0.1); // 最大仓位（10%）
    private boolean useATRStopLoss = true;                        // 使用ATR止损
    private BigDecimal atrStopLossMultiplier = BigDecimal.valueOf(2); // ATR止损倍数
    private int cooldownBars = 3;                                 // 冷却K线数
    private boolean requireVolumeConfirmation = false;             // 需要成交量确认
    private BigDecimal minVolumeRatio = BigDecimal.valueOf(1.2);  // 最小成交量倍数

    public BigDecimal getRiskPerTrade() {
        return riskPerTrade;
    }

    public void setRiskPerTrade(BigDecimal riskPerTrade) {
        this.riskPerTrade = riskPerTrade;
    }

    public BigDecimal getMaxPositionSize() {
        return maxPositionSize;
    }

    public void setMaxPositionSize(BigDecimal maxPositionSize) {
        this.maxPositionSize = maxPositionSize;
    }

    public boolean isUseATRStopLoss() {
        return useATRStopLoss;
    }

    public void setUseATRStopLoss(boolean useATRStopLoss) {
        this.useATRStopLoss = useATRStopLoss;
    }

    public BigDecimal getAtrStopLossMultiplier() {
        return atrStopLossMultiplier;
    }

    public void setAtrStopLossMultiplier(BigDecimal atrStopLossMultiplier) {
        this.atrStopLossMultiplier = atrStopLossMultiplier;
    }

    public int getCooldownBars() {
        return cooldownBars;
    }

    public void setCooldownBars(int cooldownBars) {
        this.cooldownBars = cooldownBars;
    }

    public boolean isRequireVolumeConfirmation() {
        return requireVolumeConfirmation;
    }

    public void setRequireVolumeConfirmation(boolean requireVolumeConfirmation) {
        this.requireVolumeConfirmation = requireVolumeConfirmation;
    }

    public BigDecimal getMinVolumeRatio() {
        return minVolumeRatio;
    }

    public void setMinVolumeRatio(BigDecimal minVolumeRatio) {
        this.minVolumeRatio = minVolumeRatio;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final StrategyConfig config = new StrategyConfig();

        public Builder riskPerTrade(BigDecimal value) {
            config.riskPerTrade = value;
            return this;
        }

        public Builder maxPositionSize(BigDecimal value) {
            config.maxPositionSize = value;
            return this;
        }

        public Builder useATRStopLoss(boolean value) {
            config.useATRStopLoss = value;
            return this;
        }

        public Builder atrStopLossMultiplier(BigDecimal value) {
            config.atrStopLossMultiplier = value;
            return this;
        }

        public Builder cooldownBars(int value) {
            config.cooldownBars = value;
            return this;
        }

        public Builder requireVolumeConfirmation(boolean value) {
            config.requireVolumeConfirmation = value;
            return this;
        }

        public Builder minVolumeRatio(BigDecimal value) {
            config.minVolumeRatio = value;
            return this;
        }

        public StrategyConfig build() {
            return config;
        }
    }
}
