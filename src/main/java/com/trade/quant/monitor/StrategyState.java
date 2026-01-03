package com.trade.quant.monitor;

/**
 * 策略健康状态
 * 用于策略生命周期管理和自动禁用
 */
public enum StrategyState {

    /**
     * 正常交易
     * 策略表现良好，可以正常生成信号
     */
    ENABLED,

    /**
     * 性能下降警告
     * 策略表现略有下降，但仍允许交易
     * 操作员应关注此状态
     */
    DEGRADED,

    /**
     * 已禁用
     * 策略表现严重恶化，已自动禁用
     * 不再生成信号，需要手动重新启用
     */
    DISABLED
}
