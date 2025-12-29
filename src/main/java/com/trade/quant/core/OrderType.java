package com.trade.quant.core;

/**
 * 订单类型
 * 仅支持 MARKET（市价单）和 LIMIT（限价单）
 * 禁止止盈止损单，必须由系统内部管理
 */
public enum OrderType {
    MARKET,  // 市价单，立即成交
    LIMIT    // 限价单，指定价格
}
