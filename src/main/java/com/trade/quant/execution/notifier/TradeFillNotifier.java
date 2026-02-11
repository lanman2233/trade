package com.trade.quant.execution.notifier;

import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;

import java.math.BigDecimal;

/**
 * Notifier for confirmed trade fills.
 */
public interface TradeFillNotifier {

    /**
     * Notify confirmed entry fill.
     */
    void notifyEntryFilled(String fillEventId,
                           String strategyId,
                           Symbol symbol,
                           Side side,
                           BigDecimal avgFillPrice,
                           BigDecimal filledQuantity);

    /**
     * Notify confirmed exit fill.
     */
    void notifyExitFilled(String fillEventId,
                          String strategyId,
                          Symbol symbol,
                          Side side,
                          BigDecimal avgFillPrice,
                          BigDecimal filledQuantity,
                          BigDecimal pnl);

    /**
     * Release resources.
     */
    default void close() {
    }
}

