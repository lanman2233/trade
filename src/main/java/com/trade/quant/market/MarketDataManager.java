package com.trade.quant.market;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ExchangeException;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 行情数据管理器
 * 职责：
 * 1. 维护K线数据缓存
 * 2. 补齐历史数据
 * 3. 提供数据查询接口
 */
public class MarketDataManager {

    private final Exchange exchange;
    private final Map<Symbol, Map<Interval, List<KLine>>> kLineCache;
    private final Map<Symbol, Ticker> tickerCache;
    private final Map<Symbol, Set<MarketDataListener>> listeners;

    private static final int MAX_CACHE_SIZE = 5000;

    public MarketDataManager(Exchange exchange) {
        this.exchange = exchange;
        this.kLineCache = new HashMap<>();
        this.tickerCache = new HashMap<>();
        this.listeners = new HashMap<>();
    }

    /**
     * 初始化历史数据
     * @param symbol 交易对
     * @param interval 周期
     * @param count 获取数量
     */
    public void initializeHistoricalData(Symbol symbol, Interval interval, int count) throws ExchangeException {
        List<KLine> kLines = new ArrayList<>();

        int fetched = 0;
        Long endTime = null;

        while (fetched < count) {
            int batchSize = Math.min(1000, count - fetched);
            List<KLine> batch = exchange.getKLines(symbol, interval, batchSize, endTime);

            if (batch.isEmpty()) {
                break;
            }

            kLines.addAll(0, batch); // 添加到开头
            fetched += batch.size();

            if (batch.size() < batchSize) {
                break;
            }

            endTime = batch.get(0).getOpenTime().toEpochMilli() - 1;
        }

        // 缓存数据
        kLineCache
                .computeIfAbsent(symbol, k -> new HashMap<>())
                .put(interval, new CopyOnWriteArrayList<>(kLines));
    }

    /**
     * 订阅实时K线
     */
    public void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener) {
        listeners.computeIfAbsent(symbol, k -> new HashSet<>()).add(listener);

        exchange.subscribeKLine(symbol, interval, new MarketDataListener() {
            @Override
            public void onKLine(KLine kLine) {
                // 更新缓存
                addOrUpdateKLine(kLine);

                // 通知所有监听器
                listeners.getOrDefault(symbol, Collections.emptySet())
                        .forEach(l -> {
                            try {
                                l.onKLine(kLine);
                            } catch (Exception e) {
                                l.onError(e);
                            }
                        });
            }

            @Override
            public void onTicker(Ticker ticker) {
                // Not used
            }

            @Override
            public void onError(Throwable throwable) {
                listeners.getOrDefault(symbol, Collections.emptySet())
                        .forEach(l -> l.onError(throwable));
            }
        });
    }

    /**
     * 订阅Ticker
     */
    public void subscribeTicker(Symbol symbol, MarketDataListener listener) {
        listeners.computeIfAbsent(symbol, k -> new HashSet<>()).add(listener);

        exchange.subscribeTicker(symbol, new MarketDataListener() {
            @Override
            public void onKLine(KLine kLine) {
                // Not used
            }

            @Override
            public void onTicker(Ticker ticker) {
                // 更新缓存
                tickerCache.put(symbol, ticker);

                // 通知所有监听器
                listeners.getOrDefault(symbol, Collections.emptySet())
                        .forEach(l -> {
                            try {
                                l.onTicker(ticker);
                            } catch (Exception e) {
                                l.onError(e);
                            }
                        });
            }

            @Override
            public void onError(Throwable throwable) {
                listeners.getOrDefault(symbol, Collections.emptySet())
                        .forEach(l -> l.onError(throwable));
            }
        });
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(Symbol symbol, Interval interval) {
        exchange.unsubscribeKLine(symbol, interval);
        listeners.remove(symbol);
    }

    /**
     * 取消Ticker订阅
     */
    public void unsubscribeTicker(Symbol symbol) {
        exchange.unsubscribeTicker(symbol);
    }

    /**
     * 获取K线数据
     * @param symbol 交易对
     * @param interval 周期
     * @param count 数量（返回最近的count条）
     */
    public List<KLine> getKLines(Symbol symbol, Interval interval, int count) {
        Map<Interval, List<KLine>> symbolCache = kLineCache.get(symbol);
        if (symbolCache == null) {
            return Collections.emptyList();
        }

        List<KLine> kLines = symbolCache.get(interval);
        if (kLines == null) {
            return Collections.emptyList();
        }

        int fromIndex = Math.max(0, kLines.size() - count);
        return new ArrayList<>(kLines.subList(fromIndex, kLines.size()));
    }

    /**
     * 获取所有缓存的K线数据
     */
    public List<KLine> getAllKLines(Symbol symbol, Interval interval) {
        Map<Interval, List<KLine>> symbolCache = kLineCache.get(symbol);
        if (symbolCache == null) {
            return Collections.emptyList();
        }

        List<KLine> kLines = symbolCache.get(interval);
        return kLines != null ? new ArrayList<>(kLines) : Collections.emptyList();
    }

    /**
     * 获取最新Ticker
     */
    public Optional<Ticker> getTicker(Symbol symbol) {
        return Optional.ofNullable(tickerCache.get(symbol));
    }

    /**
     * 添加或更新K线
     */
    private void addOrUpdateKLine(KLine kLine) {
        Map<Interval, List<KLine>> symbolCache = kLineCache
                .computeIfAbsent(kLine.getSymbol(), k -> new HashMap<>());

        List<KLine> kLines = symbolCache.computeIfAbsent(kLine.getInterval(), k -> new CopyOnWriteArrayList<>());

        // 检查是否需要更新最后一条K线
        if (!kLines.isEmpty()) {
            KLine lastKLine = kLines.get(kLines.size() - 1);
            if (lastKLine.getOpenTime().equals(kLine.getOpenTime())) {
                // 更新当前K线
                kLines.set(kLines.size() - 1, kLine);
                return;
            }
        }

        // 添加新K线
        kLines.add(kLine);

        // 限制缓存大小
        if (kLines.size() > MAX_CACHE_SIZE) {
            kLines.remove(0);
        }
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        kLineCache.clear();
        tickerCache.clear();
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        exchange.disconnect();
        listeners.clear();
    }
}
