package com.trade.quant.strategy;

import java.math.BigDecimal;

/**
 * 交易入场数据记录
 */
public record TradeMetrics(
        BigDecimal atrPct,
        BigDecimal rsi,
        BigDecimal ema20,
        BigDecimal ema200
) {
}
