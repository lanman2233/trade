package com.trade.quant.execution.notifier;

import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;

import java.math.BigDecimal;

/**
 * No-op implementation.
 */
public final class NoopTradeFillNotifier implements TradeFillNotifier {

    public static final NoopTradeFillNotifier INSTANCE = new NoopTradeFillNotifier();

    private NoopTradeFillNotifier() {
    }

    @Override
    public void notifyEntryFilled(String fillEventId,
                                  String strategyId,
                                  Symbol symbol,
                                  Side side,
                                  BigDecimal avgFillPrice,
                                  BigDecimal filledQuantity) {
        // no-op
    }

    @Override
    public void notifyExitFilled(String fillEventId,
                                 String strategyId,
                                 Symbol symbol,
                                 Side side,
                                 BigDecimal avgFillPrice,
                                 BigDecimal filledQuantity,
                                 BigDecimal pnl) {
        // no-op
    }
}

