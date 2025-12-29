package com.trade.quant.market;

import com.trade.quant.core.KLine;
import com.trade.quant.core.Ticker;

/**
 * 行情数据监听器
 */
public interface MarketDataListener {

    /**
     * K线数据回调
     */
    void onKLine(KLine kLine);

    /**
     * Ticker数据回调
     */
    void onTicker(Ticker ticker);

    /**
     * 错误回调
     */
    void onError(Throwable throwable);
}
