package com.trade.quant.core;

/**
 * 订单状态
 */
public enum OrderStatus {
    PENDING,    // 等待提交
    SUBMITTED,  // 已提交到交易所
    PARTIAL,    // 部分成交
    FILLED,     // 完全成交
    CANCELLED,  // 已取消
    REJECTED,   // 被拒绝
    EXPIRED     // 已过期
}
