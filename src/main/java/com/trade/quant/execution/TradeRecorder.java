package com.trade.quant.execution;

import com.trade.quant.core.Trade;

import java.util.List;

/**
 * 交易记录接口
 * 用于记录所有交易，用于复盘和分析
 */
public interface TradeRecorder {

    /**
     * 记录交易
     */
    void record(Trade trade);

    /**
     * 获取所有交易记录
     */
    List<Trade> getAllTrades();

    /**
     * 按策略ID获取交易记录
     */
    List<Trade> getTradesByStrategy(String strategyId);

    /**
     * 按时间范围获取交易记录
     */
    List<Trade> getTradesByTimeRange(long startTime, long endTime);

    /**
     * 获取交易统计
     */
    TradeStatistics getStatistics();
}
