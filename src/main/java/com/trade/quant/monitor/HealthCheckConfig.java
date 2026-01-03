package com.trade.quant.monitor;

import com.trade.quant.core.ConfigManager;

import java.math.BigDecimal;

/**
 * 健康检查配置
 * 从配置文件加载或通过 Builder 创建
 */
public class HealthCheckConfig {

    private final boolean enabled;
    private final boolean autoEnable;
    private final int minSampleSize;
    private final BigDecimal minEV;
    private final int maxConsecutiveLosses;
    private final int minEVNegativeTrades;

    private HealthCheckConfig(boolean enabled, boolean autoEnable, int minSampleSize,
                              BigDecimal minEV, int maxConsecutiveLosses,
                              int minEVNegativeTrades) {
        this.enabled = enabled;
        this.autoEnable = autoEnable;
        this.minSampleSize = minSampleSize;
        this.minEV = minEV;
        this.maxConsecutiveLosses = maxConsecutiveLosses;
        this.minEVNegativeTrades = minEVNegativeTrades;
    }

    /**
     * 从配置文件加载
     * 使用 ConfigManager 读取所有健康检查相关配置
     */
    public static HealthCheckConfig fromProperties() {
        ConfigManager config = ConfigManager.getInstance();

        return HealthCheckConfig.builder()
            .enabled(config.isFeatureEnabled("monitor.health"))
            .autoEnable(config.getBooleanProperty("monitor.health.auto.enable", true))
            .minSampleSize(config.getIntProperty("monitor.health.min.sample", 30))
            .minEV(config.getBigDecimalProperty("monitor.ev.min", BigDecimal.ZERO))
            .maxConsecutiveLosses(config.getIntProperty("monitor.health.max.consecutive.losses", 7))
            .minEVNegativeTrades(config.getIntProperty("monitor.health.min.ev.negative.trades", 30))
            .build();
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoEnable() {
        return autoEnable;
    }

    public int getMinSampleSize() {
        return minSampleSize;
    }

    public BigDecimal getMinEV() {
        return minEV;
    }

    public int getMaxConsecutiveLosses() {
        return maxConsecutiveLosses;
    }

    public int getMinEVNegativeTrades() {
        return minEVNegativeTrades;
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private boolean enabled = false;
        private boolean autoEnable = true;
        private int minSampleSize = 30;
        private BigDecimal minEV = BigDecimal.ZERO;
        private int maxConsecutiveLosses = 7;
        private int minEVNegativeTrades = 30;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder autoEnable(boolean autoEnable) {
            this.autoEnable = autoEnable;
            return this;
        }

        public Builder minSampleSize(int minSampleSize) {
            this.minSampleSize = minSampleSize;
            return this;
        }

        public Builder minEV(BigDecimal minEV) {
            this.minEV = minEV;
            return this;
        }

        public Builder maxConsecutiveLosses(int maxConsecutiveLosses) {
            this.maxConsecutiveLosses = maxConsecutiveLosses;
            return this;
        }

        public Builder minEVNegativeTrades(int minEVNegativeTrades) {
            this.minEVNegativeTrades = minEVNegativeTrades;
            return this;
        }

        public HealthCheckConfig build() {
            return new HealthCheckConfig(
                enabled, autoEnable, minSampleSize,
                minEV, maxConsecutiveLosses, minEVNegativeTrades
            );
        }
    }

    @Override
    public String toString() {
        return "HealthCheckConfig{" +
            "enabled=" + enabled +
            ", autoEnable=" + autoEnable +
            ", minSampleSize=" + minSampleSize +
            ", minEV=" + minEV +
            ", maxConsecutiveLosses=" + maxConsecutiveLosses +
            ", minEVNegativeTrades=" + minEVNegativeTrades +
            '}';
    }
}
