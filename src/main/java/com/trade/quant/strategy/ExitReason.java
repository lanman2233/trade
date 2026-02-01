package com.trade.quant.strategy;

/**
 * 鍑哄満鍘熷洜
 */
public enum ExitReason {
    TAKE_PROFIT,
    STOP_LOSS,
    TIME_STOP,
    TRAILING_STOP,
    STRATEGY_EXIT,
    FORCE_CLOSE
}
