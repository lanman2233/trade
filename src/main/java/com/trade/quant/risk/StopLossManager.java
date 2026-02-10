package com.trade.quant.risk;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 止损管理器
 * 负责监控持仓并触发止损
 */
public class StopLossManager {

    private static final Logger logger = LoggerFactory.getLogger(StopLossManager.class);

    private final Exchange exchange;
    private final Map<String, Position> monitoredPositions;
    private final Map<String, BigDecimal> stopLossPrices;
    private boolean running;

    public StopLossManager(Exchange exchange) {
        this.exchange = exchange;
        this.monitoredPositions = new ConcurrentHashMap<>();
        this.stopLossPrices = new ConcurrentHashMap<>();
        this.running = false;
    }

    /**
     * 添加持仓监控
     */
    public void monitor(Position position, BigDecimal stopLossPrice) {
        String key = position.getSymbol().toPairString();
        monitoredPositions.put(key, position);
        stopLossPrices.put(key, stopLossPrice);
        logger.info("开始监控持仓止损: {} @ {}", position.getSymbol(), stopLossPrice);
    }

    /**
     * 移除监控
     */
    public void remove(Symbol symbol) {
        String key = symbol.toPairString();
        Position removedPosition = monitoredPositions.remove(key);
        BigDecimal removedStop = stopLossPrices.remove(key);
        if (removedPosition != null || removedStop != null) {
            logger.info("移除止损监控: {}", symbol);
        }
    }

    /**
     * 检查止损触发
     * @return 需要平仓的持仓列表
     */
    public List<Position> checkStopLoss(Ticker ticker) {
        List<Position> toClose = new ArrayList<>();

        Symbol symbol = ticker.getSymbol();
        Position position = monitoredPositions.get(symbol.toPairString());

        if (position == null || position.isClosed()) {
            return toClose;
        }

        BigDecimal stopLoss = stopLossPrices.get(symbol.toPairString());
        if (stopLoss == null) {
            return toClose;
        }

        // 更新未实现盈亏
        position.updateUnrealizedPnl(ticker.getLastPrice());

        // 检查止损触发
        if (position.isStopLossTriggered(ticker.getLastPrice())) {
            logger.warn("止损触发! {} 当前价格: {}, 止损价格: {}",
                    symbol, ticker.getLastPrice(), stopLoss);
            toClose.add(position);
            remove(symbol); // 移除监控
        }

        return toClose;
    }

    /**
     * 追踪止损（移动止损）
     */
    public void updateTrailingStop(Symbol symbol, BigDecimal newStopLoss) {
        String key = symbol.toPairString();
        Position position = monitoredPositions.get(key);

        if (position != null) {
            BigDecimal currentStop = stopLossPrices.get(key);

            // 只向有利方向移动
            if (position.getSide() == PositionSide.LONG) {
                if (newStopLoss.compareTo(currentStop) > 0) {
                    stopLossPrices.put(key, newStopLoss);
                    logger.info("更新追踪止损: {} {} -> {}", symbol, currentStop, newStopLoss);
                }
            } else {
                if (newStopLoss.compareTo(currentStop) < 0) {
                    stopLossPrices.put(key, newStopLoss);
                    logger.info("更新追踪止损: {} {} -> {}", symbol, currentStop, newStopLoss);
                }
            }
        }
    }

    /**
     * 启动监控
     */
    public void start() {
        running = true;
        logger.info("止损管理器已启动");
    }

    /**
     * 停止监控
     */
    public void stop() {
        running = false;
        monitoredPositions.clear();
        stopLossPrices.clear();
        logger.info("止损管理器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public int getMonitoringCount() {
        return monitoredPositions.size();
    }
}
