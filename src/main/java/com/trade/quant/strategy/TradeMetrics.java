package com.trade.quant.strategy;

import java.math.BigDecimal;

/**
 * 浜ゆ槗鍏ュ満鏁版嵁璁板綍
 */
public record TradeMetrics(
        BigDecimal atrPct,
        BigDecimal rsi,
        BigDecimal ema20,
        BigDecimal ema200
) {
}
