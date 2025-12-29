package com.trade.quant.strategy;

/**
 * 信号类型
 */
public enum SignalType {
    ENTRY_LONG,    // 做多入场
    ENTRY_SHORT,   // 做空入场
    EXIT_LONG,     // 多头出场
    EXIT_SHORT,    // 空头出场
    NONE           // 无信号
}
