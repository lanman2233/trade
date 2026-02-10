package com.trade.quant.strategy;

import java.math.BigDecimal;

/**
 * 可接收账户权益更新的策略（用于实盘动态仓位）
 */
public interface EquityAwareStrategy {

    /**
     * 更新当前账户权益（USDT）
     */
    void updateEquity(BigDecimal equity);
}
