package com.trade.quant.backtest;

import com.trade.quant.core.KLine;
import com.trade.quant.core.Position;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.TradeMetrics;

/**
 * 回测交易事件监听
 */
public interface BacktestTradeListener {

    default void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
    }

    default void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
    }
}
