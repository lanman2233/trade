package com.trade.quant.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 健康检查结果
 * 包含策略健康评估的详细信息
 */
public class HealthCheckResult {

    private final boolean shouldDisable;
    private final boolean degraded;
    private final List<String> reasons;

    public HealthCheckResult(boolean shouldDisable, boolean degraded, List<String> reasons) {
        this.shouldDisable = shouldDisable;
        this.degraded = degraded;
        this.reasons = reasons != null ? new ArrayList<>(reasons) : new ArrayList<>();
    }

    /**
     * 创建健康结果（无问题）
     */
    public static HealthCheckResult healthy() {
        return new HealthCheckResult(false, false, Collections.emptyList());
    }

    /**
     * 创建需禁用结果
     */
    public static HealthCheckResult disable(List<String> reasons) {
        return new HealthCheckResult(true, false, reasons);
    }

    /**
     * 创建降级警告结果
     */
    public static HealthCheckResult degraded(List<String> reasons) {
        return new HealthCheckResult(false, true, reasons);
    }

    /**
     * 判断是否健康（无问题）
     */
    public boolean isHealthy() {
        return !shouldDisable && !degraded;
    }

    /**
     * 判断是否应该禁用策略
     */
    public boolean shouldDisable() {
        return shouldDisable;
    }

    /**
     * 判断是否为降级状态
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 获取原因列表
     */
    public List<String> getReasons() {
        return Collections.unmodifiableList(reasons);
    }

    @Override
    public String toString() {
        if (isHealthy()) {
            return "HealthCheckResult{HEALTHY}";
        } else if (shouldDisable) {
            return "HealthCheckResult{DISABLE, reasons=" + reasons + "}";
        } else {
            return "HealthCheckResult{DEGRADED, reasons=" + reasons + "}";
        }
    }
}
