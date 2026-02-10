package com.trade.quant.strategy;

import com.trade.quant.core.*;
import com.trade.quant.market.MarketDataManager;
import com.trade.quant.market.MarketDataListener;
import com.trade.quant.monitor.StrategyHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 策略引擎
 *
 * 职责：
 * 1. 管理多个策略实例
 * 2. 统一处理市场数据
 * 3. 产出交易信号
 * 4. 分发信号给风控模块
 * 5. 检查策略健康状态（集成 StrategyHealthChecker）
 *
 * 架构：StrategyEngine 只产出「交易意图」，不执行交易
 */
public class StrategyEngine {

    private static final Logger logger = LoggerFactory.getLogger(StrategyEngine.class);

    private final List<Strategy> strategies;
    private final MarketDataManager marketDataManager;
    private final List<SignalListener> signalListeners;
    private volatile Map<String, Position> openPositions;
    private final Map<String, Instant> lastBarOpenTime;
    private boolean running;
    private StrategyHealthChecker healthChecker;

    public StrategyEngine(MarketDataManager marketDataManager) {
        this.strategies = new CopyOnWriteArrayList<>();
        this.marketDataManager = marketDataManager;
        this.signalListeners = new CopyOnWriteArrayList<>();
        this.openPositions = new ConcurrentHashMap<>();
        this.lastBarOpenTime = new ConcurrentHashMap<>();
        this.running = false;
    }

    /**
     * 添加策略
     */
    public void addStrategy(Strategy strategy) {
        strategy.initialize();
        strategies.add(strategy);

        // 注册到健康检查器（如果已配置）
        if (healthChecker != null) {
            healthChecker.registerStrategy(strategy);
        }

        logger.info("策略已添加: {} ({})", strategy.getName(), strategy.getStrategyId());
    }

    /**
     * 移除策略
     */
    public void removeStrategy(String strategyId) {
        strategies.removeIf(s -> s.getStrategyId().equals(strategyId));
        lastBarOpenTime.remove(strategyId);
        logger.info("策略已移除: {}", strategyId);
    }

    /**
     * 添加信号监听器
     */
    public void addSignalListener(SignalListener listener) {
        signalListeners.add(listener);
    }

    public void removeSignalListener(SignalListener listener) {
        signalListeners.remove(listener);
    }

    /**
     * 启动引擎
     */
    public void start() {
        if (running) {
            logger.warn("策略引擎已在运行中");
            return;
        }

        running = true;

        // 为每个策略订阅K线数据
        for (Strategy strategy : strategies) {
            Symbol symbol = strategy.getSymbol();
            Interval interval = strategy.getInterval();

            marketDataManager.subscribeKLine(symbol, interval, new MarketDataListener() {
                @Override
                public void onKLine(KLine kLine) {
                    if (!running) return;

                    try {
                        processStrategy(strategy, kLine);
                    } catch (Exception e) {
                        logger.error("处理策略 {} 时出错: {}", strategy.getStrategyId(), e.getMessage(), e);
                    }
                }

                @Override
                public void onTicker(Ticker ticker) {
                    // Not used
                }

                @Override
                public void onError(Throwable throwable) {
                    logger.error("K线订阅错误: {}", throwable.getMessage());
                }
            });
        }

        logger.info("策略引擎已启动，共 {} 个策略", strategies.size());
    }

    /**
     * 停止引擎
     */
    public void stop() {
        running = false;
        for (Strategy strategy : strategies) {
            marketDataManager.unsubscribe(strategy.getSymbol(), strategy.getInterval());
        }
        logger.info("策略引擎已停止");
    }

    /**
     * 更新持仓状态
     */
    public void updatePositions(List<Position> positions) {
        Map<String, Position> latestPositions = new ConcurrentHashMap<>();
        if (positions == null) {
            openPositions = latestPositions;
            return;
        }
        for (Position position : positions) {
            String key = position.getSymbol().toPairString();
            latestPositions.put(key, position);
        }
        openPositions = latestPositions;
    }

    /**
     * 设置健康检查器
     * 用于策略生命周期管理
     *
     * @param healthChecker 健康检查器（可选）
     */
    public void setHealthChecker(StrategyHealthChecker healthChecker) {
        this.healthChecker = healthChecker;
        logger.info("健康检查器已{}", healthChecker != null ? "设置" : "清除");
    }

    /**
     * 处理单个策略
     */
    private void processStrategy(Strategy strategy, KLine kLine) {
        if (strategy instanceof AbstractStrategy abstractStrategy) {
            Instant openTime = kLine.getOpenTime();
            Instant previous = lastBarOpenTime.put(strategy.getStrategyId(), openTime);
            if (previous == null || !previous.equals(openTime)) {
                abstractStrategy.incrementBarCount();
            }
        }
        // 检查健康状态（如果已配置健康检查器）
        if (healthChecker != null && !healthChecker.isStrategyEnabled(strategy.getStrategyId())) {
            logger.debug("策略 {} 已禁用，跳过处理", strategy.getStrategyId());
            return;
        }

        // 获取K线数据
        List<KLine> kLines = marketDataManager.getAllKLines(strategy.getSymbol(), strategy.getInterval());
        if (kLines.isEmpty()) {
            logger.warn("策略 {} 没有可用的K线数据", strategy.getStrategyId());
            return;
        }

        // 检查是否有持仓
        Position position = openPositions.get(strategy.getSymbol().toPairString());

        Signal signal;
        if (position != null && !position.isClosed()) {
            // 有持仓，调用持仓更新方法
            signal = strategy.onPositionUpdate(position, kLine, kLines);
        } else {
            // 冷却期只限制新开仓，不限制持仓管理与出场
            if (strategy.isInCooldown()) {
                logger.debug("策略 {} 处于冷却期，跳过新开仓", strategy.getStrategyId());
                return;
            }
            // 无持仓，分析市场数据
            signal = strategy.analyze(kLines);
        }

        if (signal != null) {
            logger.info("策略 {} 产生信号: {}", strategy.getStrategyId(), signal);
            notifySignalListeners(signal);
        }
    }

    /**
     * 通知信号监听器
     */
    private void notifySignalListeners(Signal signal) {
        for (SignalListener listener : signalListeners) {
            try {
                listener.onSignal(signal);
            } catch (Exception e) {
                logger.error("信号监听器处理失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 信号监听器接口
     */
    public interface SignalListener {
        void onSignal(Signal signal);
    }

    public List<Strategy> getStrategies() {
        return new ArrayList<>(strategies);
    }

    public boolean isRunning() {
        return running;
    }
}
