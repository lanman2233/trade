package com.trade.quant.exchange;

import com.trade.quant.core.*;
import com.trade.quant.market.MarketDataListener;

import java.util.List;

/**
 * 交易所抽象接口
 * 策略层禁止直接调用交易所SDK，必须通过此接口
 *
 * 架构：Exchange 只负责执行，不做逻辑判断
 */
public interface Exchange {

    /**
     * 获取交易所名称
     */
    String getName();

    /**
     * 获取账户信息
     */
    AccountInfo getAccountInfo() throws ExchangeException;

    /**
     * 获取实时行情
     */
    Ticker getTicker(Symbol symbol) throws ExchangeException;

    /**
     * 获取历史K线数据
     * @param symbol 交易对
     * @param interval 周期
     * @param limit 数量限制（最大1000）
     * @param endTime 结束时间（毫秒），null表示最新
     */
    List<KLine> getKLines(Symbol symbol, Interval interval, int limit, Long endTime) throws ExchangeException;

    /**
     * 下单
     * @param order 订单（必须包含止损价格）
     * @return 实际的订单ID
     */
    String placeOrder(Order order) throws ExchangeException;

    /**
     * 取消订单
     */
    boolean cancelOrder(String orderId, Symbol symbol) throws ExchangeException;

    /**
     * 查询订单状态
     */
    Order getOrder(String orderId, Symbol symbol) throws ExchangeException;

    /**
     * 获取当前持仓
     */
    List<Position> getOpenPositions(Symbol symbol) throws ExchangeException;

    /**
     * 获取交易历史
     */
    List<Trade> getTradeHistory(Symbol symbol, int limit) throws ExchangeException;

    /**
     * 订阅实时行情（WebSocket）
     * @param symbol 交易对
     * @param interval K线周期
     * @param listener 回调监听器
     */
    void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener);

    /**
     * 订阅Ticker（WebSocket）
     */
    void subscribeTicker(Symbol symbol, MarketDataListener listener);

    /**
     * 取消订阅
     */
    void unsubscribeKLine(Symbol symbol, Interval interval);

    /**
     * 取消Ticker订阅
     */
    void unsubscribeTicker(Symbol symbol);

    /**
     * 启动WebSocket连接
     */
    void connect() throws ExchangeException;

    /**
     * 断开WebSocket连接
     */
    void disconnect();

    /**
     * 检查连接状态
     */
    boolean isConnected();

    /**
     * 设置API密钥
     */
    void setApiKey(String apiKey, String secretKey, String passphrase);

    /**
     * 设置代理（可选）
     */
    void setProxy(String host, int port);
}
