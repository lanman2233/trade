package com.trade.quant.execution;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ProtectiveStopCapableExchange;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.EquityAwareStrategy;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.Strategy;
import com.trade.quant.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live trading execution engine.
 * Handles signal execution, position sync, and exchange stop orchestration. */
public class TradingEngine {

    private static final Logger logger = LoggerFactory.getLogger(TradingEngine.class);
    private static final long ENTRY_ADOPTION_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int ORDER_QUERY_MAX_RETRIES = 3;
    private static final long ORDER_QUERY_RETRY_DELAY_MS = 120L;
    private static final long TRANSIENT_SYNC_LOG_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final StrategyEngine strategyEngine;
    private final RiskControl riskControl;
    private final OrderExecutor orderExecutor;
    private final StopLossManager stopLossManager;
    private final Exchange exchange;
    private final Map<String, Position> trackedPositions;
    private final Map<String, String> trackedPositionStrategyIds;
    private final Map<String, PendingEntryContext> pendingEntryContexts;
    private final Map<String, ExchangeStopState> exchangeStopStates;
    private final boolean orphanPositionAutoAdoptEnabled;
    private final BigDecimal orphanPositionStopLossPercent;
    private final StrategyEngine.SignalListener signalListener;
    private ScheduledExecutorService positionSyncExecutor;
    private volatile long lastPositionSyncTransientLogAtMillis;
    private boolean running;

    private record PendingEntryContext(String strategyId, BigDecimal stopLoss, PositionSide side, long createdAtMillis) {}
    private record ExchangeStopState(String orderId, BigDecimal stopPrice, BigDecimal quantity) {}

    public TradingEngine(StrategyEngine strategyEngine, RiskControl riskControl,
                         OrderExecutor orderExecutor, StopLossManager stopLossManager,
                         Exchange exchange) {
        this.strategyEngine = strategyEngine;
        this.riskControl = riskControl;
        this.orderExecutor = orderExecutor;
        this.stopLossManager = stopLossManager;
        this.exchange = exchange;
        this.running = false;
        this.trackedPositions = new ConcurrentHashMap<>();
        this.trackedPositionStrategyIds = new ConcurrentHashMap<>();
        this.pendingEntryContexts = new ConcurrentHashMap<>();
        this.exchangeStopStates = new ConcurrentHashMap<>();
        ConfigManager cfg = ConfigManager.getInstance();
        this.orphanPositionAutoAdoptEnabled = cfg.getBooleanProperty("live.orphan.position.adopt", true);
        this.orphanPositionStopLossPercent = cfg.getBigDecimalProperty(
                "live.orphan.position.stop.loss.percent",
                new BigDecimal("0.02")
        );
        this.signalListener = this::onSignal;
        this.positionSyncExecutor = null;
        this.lastPositionSyncTransientLogAtMillis = 0L;
    }

    /**
     * Start trading engine and all required background components.
     */
    public void start() {
        if (running) {
            logger.warn("Trading engine is already running");
            return;
        }

        running = true;
        stopLossManager.start();
        strategyEngine.addSignalListener(signalListener);
        strategyEngine.start();
        startPositionSyncLoop();

        logger.info("Trading engine started");
    }

    /**
     * Stop trading engine and release background resources.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        stopPositionSyncLoop();
        strategyEngine.removeSignalListener(signalListener);
        strategyEngine.stop();
        stopLossManager.stop();

        logger.info("Trading engine stopped");
    }

    /**
     * Handle incoming strategy signal and execute order workflow.
     */
    private void onSignal(Signal signal) {
        if (!running) {
            return;
        }

        try {
            logger.info("收到信号: {}", signal);

            AccountInfo info = null;
            try {
                info = exchange.getAccountInfo();
                riskControl.updateAccountInfo(info);
                updateStrategyEquity(info);
            } catch (Exception e) {
                    logger.warn("Position sync temporarily unavailable, will retry: {}", e.getMessage());
            }

            List<Position> positions = exchange.getOpenPositions(signal.getSymbol());
            Order order = riskControl.validateAndCreateOrder(signal, positions);
            if (order == null) {
                logger.info("Order rejected by risk control");
                return;
            }

            String orderId = orderExecutor.submitOrder(order);
            logger.info("订单已提交: {}", orderId);

            if (signal.isEntry()) {
                handleEntrySignal(signal, order, orderId);
            } else if (signal.isExit()) {
                handleExitSignal(signal, order, orderId);
            }

        } catch (Exception e) {
            logger.error("Signal handling failed: {}", e.getMessage(), e);
        }
    }

    private void handleEntrySignal(Signal signal, Order order, String orderId) {
        String symbolKey = signal.getSymbol().toPairString();
        PositionSide entrySide = signal.getSide() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
        pendingEntryContexts.put(symbolKey,
                new PendingEntryContext(signal.getStrategyId(), signal.getStopLoss(), entrySide, System.currentTimeMillis()));

        BigDecimal entryPrice = signal.getPrice();
        BigDecimal quantity = BigDecimal.ZERO;

        Order executed = fetchExecutedOrder(order, orderId, signal.getSymbol());
        if (executed != null) {
            if (executed.getAvgFillPrice() != null && executed.getAvgFillPrice().compareTo(BigDecimal.ZERO) > 0) {
                entryPrice = executed.getAvgFillPrice();
            }
            if (executed.getFilledQuantity() != null && executed.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
                quantity = executed.getFilledQuantity();
            }
        }

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            try {
                Position matched = findMatchingPosition(exchange.getOpenPositions(signal.getSymbol()), entrySide);
                if (matched != null && matched.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    entryPrice = matched.getEntryPrice();
                    quantity = matched.getQuantity();
                }
            } catch (Exception e) {
                logger.warn("查询持仓失败，无法回填成交价格和数量: {}", e.getMessage());
            }
        }

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("开仓订单未确认成交，跳过本地持仓接管: symbol={}, orderId={}", signal.getSymbol(), orderId);
            return;
        }

        Position pos = new Position(
                signal.getSymbol(),
                entrySide,
                entryPrice,
                quantity,
                signal.getStopLoss(),
                BigDecimal.ONE
        );
        armStopProtection(pos);
        trackedPositions.put(symbolKey, pos);
        trackedPositionStrategyIds.put(symbolKey, signal.getStrategyId());
        pendingEntryContexts.remove(symbolKey);
        syncStrategyPositions();
    }

    private void handleExitSignal(Signal signal, Order order, String orderId) {
        String symbolKey = signal.getSymbol().toPairString();
        Position tracked = trackedPositions.get(symbolKey);

        BigDecimal exitPrice = signal.getPrice();
        BigDecimal exitQty = BigDecimal.ZERO;

        Order executed = fetchExecutedOrder(order, orderId, signal.getSymbol());
        if (executed != null) {
            if (executed.getAvgFillPrice() != null && executed.getAvgFillPrice().compareTo(BigDecimal.ZERO) > 0) {
                exitPrice = executed.getAvgFillPrice();
            }
            if (executed.getFilledQuantity() != null && executed.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
                exitQty = executed.getFilledQuantity();
            }
        }

        if (tracked != null && exitQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal actualQty = exitQty.min(tracked.getQuantity());
            BigDecimal pnl = calculatePnl(tracked, exitPrice, actualQty);
            String strategyId = trackedPositionStrategyIds.getOrDefault(symbolKey, signal.getStrategyId());
            riskControl.recordTradeResult(strategyId, pnl.compareTo(BigDecimal.ZERO) > 0, pnl);
            tracked.reduce(actualQty);
            if (!tracked.isClosed()) {
                armStopProtection(tracked);
            }
        }

        // Do not clear local position eagerly. Reconcile with exchange state first.
        reconcileTrackedPosition(signal.getSymbol());
        syncStrategyPositions();
    }

    private Order fetchExecutedOrder(Order order, String orderId, Symbol symbol) {
        String exchangeOrderId = order.getExchangeOrderId() != null ? order.getExchangeOrderId() : orderId;
        Exception lastException = null;
        for (int attempt = 1; attempt <= ORDER_QUERY_MAX_RETRIES; attempt++) {
            try {
                return exchange.getOrder(exchangeOrderId, symbol);
            } catch (Exception e) {
                lastException = e;
                if (attempt < ORDER_QUERY_MAX_RETRIES) {
                    try {
                        Thread.sleep(ORDER_QUERY_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        String reason = lastException == null ? "unknown" : lastException.getMessage();
        logger.warn("查询成交明细失败: orderId={}, exchangeOrderId={}, reason={}", orderId, exchangeOrderId, reason);
        return null;
    }

    private void updateStrategyEquity(AccountInfo info) {
        if (info == null) {
            return;
        }
        for (Strategy strategy : strategyEngine.getStrategies()) {
            if (strategy instanceof EquityAwareStrategy equityAware) {
                equityAware.updateEquity(info.getTotalBalance());
            }
        }
    }

    private void syncStrategyPositions() {
        strategyEngine.updatePositions(List.copyOf(trackedPositions.values()));
    }

    /**
     * Position sync loop: reconcile exchange/local positions and stop state.     */
    public void updatePositions() {
        if (!running) {
            return;
        }

        try {
            for (Symbol symbol : collectSymbolsToSync()) {
                List<Position> positions = exchange.getOpenPositions(symbol);
                String symbolKey = symbol.toPairString();

                if (positions == null || positions.isEmpty()) {
                    removeTrackedPosition(symbolKey, symbol);
                    cleanupExpiredPendingEntry(symbolKey);
                    continue;
                }

                Position tracked = trackedPositions.get(symbolKey);
                if (tracked == null) {
                    if (!adoptPendingEntryPosition(symbol, positions)) {
                        adoptOrphanPosition(symbol, positions);
                    }
                    continue;
                }

                Position exchangePosition = findMatchingPosition(positions, tracked.getSide());
                if (exchangePosition == null) {
                    removeTrackedPosition(symbolKey, symbol);
                    continue;
                }

                syncTrackedQuantity(symbolKey, tracked, exchangePosition);
                Position latestTracked = trackedPositions.get(symbolKey);
                armStopProtection(latestTracked != null ? latestTracked : tracked);

                Ticker ticker = exchange.getTicker(symbol);
                List<Position> toClose = stopLossManager.checkStopLoss(ticker);
                for (Position pos : toClose) {
                    closePosition(pos, ticker.getLastPrice());
                }
            }
            syncStrategyPositions();

        } catch (Exception e) {
            if (isTransientPositionSyncFailure(e)) {
                long now = System.currentTimeMillis();
                if (now - lastPositionSyncTransientLogAtMillis >= TRANSIENT_SYNC_LOG_INTERVAL_MS) {
                    lastPositionSyncTransientLogAtMillis = now;
                    logger.warn("Position sync temporarily unavailable, will retry: {}", e.getMessage());
                } else {
                    logger.debug("Position sync temporarily unavailable (suppressed): {}", e.getMessage());
                }
                return;
            }
            logger.error("Position sync failed: {}", e.getMessage(), e);
        }
    }


    private boolean isTransientPositionSyncFailure(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("HTTP 503")
                    || message.contains("\"code\":\"50001\"")
                    || message.contains("Service temporarily unavailable"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean adoptPendingEntryPosition(Symbol symbol, List<Position> exchangePositions) {
        String symbolKey = symbol.toPairString();
        PendingEntryContext context = pendingEntryContexts.get(symbolKey);
        if (context == null) {
            return false;
        }

        if (System.currentTimeMillis() - context.createdAtMillis() > ENTRY_ADOPTION_TTL_MS) {
            pendingEntryContexts.remove(symbolKey);
            logger.warn("开仓待接管上下文已过期，放弃接管: symbol={}", symbol);
            return false;
        }

        Position matched = findMatchingPosition(exchangePositions, context.side());
        if (matched == null) {
            logger.warn("未找到与待接管方向一致的交易所持仓，稍后重试: symbol={}", symbol);
            return false;
        }

        Position adopted = new Position(
                symbol,
                matched.getSide(),
                matched.getEntryPrice(),
                matched.getQuantity(),
                context.stopLoss(),
                matched.getLeverage()
        );

        trackedPositions.put(symbolKey, adopted);
        trackedPositionStrategyIds.put(symbolKey, context.strategyId());
        pendingEntryContexts.remove(symbolKey);

        armStopProtection(adopted);

        logger.info("已接管开仓后持仓: symbol={}, qty={}, strategyId={}",
                symbol, adopted.getQuantity(), context.strategyId());
        return true;
    }

    private void adoptOrphanPosition(Symbol symbol, List<Position> exchangePositions) {
        if (!orphanPositionAutoAdoptEnabled) {
            logger.warn("检测到未跟踪持仓，但已关闭自动接管: symbol={}", symbol);
            return;
        }
        if (exchangePositions == null || exchangePositions.isEmpty()) {
            return;
        }
        Position matched = selectPrimaryExchangePosition(exchangePositions);
        if (matched == null || matched.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal fallbackStop = buildFallbackStopLoss(matched);
        if (fallbackStop.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("孤儿持仓兜底止损无效，跳过接管: symbol={}", symbol);
            return;
        }

        String strategyId = resolveStrategyIdForSymbol(symbol);
        Position adopted = new Position(
                symbol,
                matched.getSide(),
                matched.getEntryPrice(),
                matched.getQuantity(),
                fallbackStop,
                matched.getLeverage()
        );

        String symbolKey = symbol.toPairString();
        trackedPositions.put(symbolKey, adopted);
        trackedPositionStrategyIds.put(symbolKey, strategyId);
        armStopProtection(adopted);
        syncStrategyPositions();

        logger.warn("检测到孤儿持仓，已按兜底参数接管: symbol={}, side={}, qty={}, entry={}, fallbackStop={}, strategyId={}",
                symbol, adopted.getSide(), adopted.getQuantity(), adopted.getEntryPrice(), fallbackStop, strategyId);
    }

    private Position selectPrimaryExchangePosition(List<Position> positions) {
        Position selected = null;
        for (Position position : positions) {
            if (position == null || position.isClosed()) {
                continue;
            }
            if (selected == null || position.getQuantity().compareTo(selected.getQuantity()) > 0) {
                selected = position;
            }
        }
        return selected;
    }

    private BigDecimal buildFallbackStopLoss(Position position) {
        BigDecimal entry = position.getEntryPrice();
        if (entry == null || entry.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pct = sanitizeFallbackStopPercent(orphanPositionStopLossPercent);
        BigDecimal multiplier = position.getSide() == PositionSide.LONG
                ? BigDecimal.ONE.subtract(pct)
                : BigDecimal.ONE.add(pct);
        BigDecimal stop = entry.multiply(multiplier);
        return Decimal.scalePrice(stop);
    }

    private BigDecimal sanitizeFallbackStopPercent(BigDecimal raw) {
        if (raw == null || raw.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.02");
        }
        BigDecimal max = new BigDecimal("0.50");
        if (raw.compareTo(max) > 0) {
            return max;
        }
        return raw;
    }

    private void syncTrackedQuantity(String symbolKey, Position tracked, Position exchangePosition) {
        BigDecimal trackedQty = tracked.getQuantity();
        BigDecimal exchangeQty = exchangePosition.getQuantity();

        int compare = exchangeQty.compareTo(trackedQty);
        if (compare < 0) {
            BigDecimal delta = trackedQty.subtract(exchangeQty);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                tracked.reduce(delta);
            }
            if (tracked.isClosed()) {
                removeTrackedPosition(symbolKey, tracked.getSymbol());
            } else {
                armStopProtection(tracked);
            }
            return;
        }

        if (compare > 0) {
            // Sync up to exchange quantity when local side exists but local qty lags.
            Position replacement = new Position(
                    tracked.getSymbol(),
                    tracked.getSide(),
                    exchangePosition.getEntryPrice(),
                    exchangeQty,
                    tracked.getStopLoss(),
                    exchangePosition.getLeverage()
            );
            trackedPositions.put(symbolKey, replacement);
            armStopProtection(replacement);
        }
    }

    private void reconcileTrackedPosition(Symbol symbol) {
        String symbolKey = symbol.toPairString();
        Position tracked = trackedPositions.get(symbolKey);
        if (tracked == null) {
            return;
        }

        try {
            List<Position> exchangePositions = exchange.getOpenPositions(symbol);
            if (exchangePositions == null || exchangePositions.isEmpty()) {
                removeTrackedPosition(symbolKey, symbol);
                return;
            }

            Position matched = findMatchingPosition(exchangePositions, tracked.getSide());
            if (matched == null) {
                removeTrackedPosition(symbolKey, symbol);
                return;
            }

            syncTrackedQuantity(symbolKey, tracked, matched);
        } catch (Exception e) {
            logger.warn("对账本地持仓与交易所持仓失败，稍后重试: {}", e.getMessage());
        }
    }

    private void removeTrackedPosition(String symbolKey, Symbol symbol) {
        cancelExchangeStop(symbol);
        trackedPositions.remove(symbolKey);
        trackedPositionStrategyIds.remove(symbolKey);
        stopLossManager.remove(symbol);
    }

    private void cleanupExpiredPendingEntry(String symbolKey) {
        PendingEntryContext context = pendingEntryContexts.get(symbolKey);
        if (context != null && System.currentTimeMillis() - context.createdAtMillis() > ENTRY_ADOPTION_TTL_MS) {
            pendingEntryContexts.remove(symbolKey);
        }
    }

    private Position findMatchingPosition(List<Position> positions, PositionSide side) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        for (Position position : positions) {
            if (position.getSide() == side && !position.isClosed()) {
                return position;
            }
        }
        return null;
    }

    private void startPositionSyncLoop() {
        if (positionSyncExecutor != null) {
            return;
        }
        positionSyncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "position-sync");
            thread.setDaemon(true);
            return thread;
        });
        positionSyncExecutor.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            updatePositions();
        }, 1, 2, TimeUnit.SECONDS);
    }

    private void stopPositionSyncLoop() {
        if (positionSyncExecutor == null) {
            return;
        }
        positionSyncExecutor.shutdownNow();
        positionSyncExecutor = null;
    }

    private Set<Symbol> collectSymbolsToSync() {
        Set<Symbol> symbols = new HashSet<>();
        for (Strategy strategy : strategyEngine.getStrategies()) {
            symbols.add(strategy.getSymbol());
        }
        for (Position position : trackedPositions.values()) {
            symbols.add(position.getSymbol());
        }
        return symbols;
    }

    private BigDecimal calculatePnl(Position position, BigDecimal exitPrice, BigDecimal quantity) {
        if (position == null || exitPrice == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        if (quantity.compareTo(BigDecimal.ZERO) <= 0 || exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal priceDiff = position.getSide() == PositionSide.LONG
                ? exitPrice.subtract(position.getEntryPrice())
                : position.getEntryPrice().subtract(exitPrice);
        return priceDiff.multiply(quantity);
    }

    /**
     * Close position by market order when local stop-loss is triggered.     */
    private void closePosition(Position position, BigDecimal price) {
        try {
            String strategyId = resolveStrategyIdForPosition(position);
            Side side = position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY;
            cancelExchangeStop(position.getSymbol());

            Order closeOrder = Order.builder()
                    .orderId(java.util.UUID.randomUUID().toString())
                    .symbol(position.getSymbol())
                    .side(side)
                    .type(OrderType.MARKET)
                    .quantity(position.getQuantity())
                    .price(BigDecimal.ZERO)
                    .stopLoss(BigDecimal.ZERO)
                    .takeProfit(BigDecimal.ZERO)
                    .strategyId("STOP_LOSS")
                    .reduceOnly(true)
                    .build();

            String orderId = orderExecutor.submitOrder(closeOrder);

            BigDecimal exitPrice = price;
            BigDecimal exitQty = BigDecimal.ZERO;
            Order executed = fetchExecutedOrder(closeOrder, orderId, position.getSymbol());
            if (executed != null) {
                if (executed.getAvgFillPrice() != null && executed.getAvgFillPrice().compareTo(BigDecimal.ZERO) > 0) {
                    exitPrice = executed.getAvgFillPrice();
                }
                if (executed.getFilledQuantity() != null && executed.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    exitQty = executed.getFilledQuantity().min(position.getQuantity());
                }
            }

            if (exitQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pnl = calculatePnl(position, exitPrice, exitQty);
                riskControl.recordTradeResult(strategyId, pnl.compareTo(BigDecimal.ZERO) > 0, pnl);
                logger.warn("止损平仓: {} {} @ {}, strategyId={}, pnl={}",
                        position.getSymbol(), exitQty, exitPrice, strategyId, pnl);
                position.reduce(exitQty);
            } else {
                logger.warn("止损平仓订单未确认成交，保留现有持仓并等待后续同步: {}", position.getSymbol());
            }

            reconcileTrackedPosition(position.getSymbol());

        } catch (Exception e) {
            logger.error("止损平仓失败: {}", e.getMessage(), e);
        }
    }

    private void armStopProtection(Position position) {
        if (position == null || position.isClosed()) {
            return;
        }
        BigDecimal stopLoss = position.getStopLoss();
        if (stopLoss == null || stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (placeOrReplaceExchangeStop(position, stopLoss)) {
            stopLossManager.remove(position.getSymbol());
            return;
        }
        stopLossManager.monitor(position, stopLoss);
    }

    private boolean placeOrReplaceExchangeStop(Position position, BigDecimal stopLoss) {
        if (!(exchange instanceof ProtectiveStopCapableExchange stopCapableExchange)) {
            return false;
        }
        String symbolKey = position.getSymbol().toPairString();
        Side closeSide = position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY;
        BigDecimal normalizedStop;
        BigDecimal normalizedQty;
        try {
            normalizedStop = stopCapableExchange.normalizeStopPrice(position.getSymbol(), closeSide, stopLoss);
            normalizedQty = stopCapableExchange.normalizeMarketQuantity(position.getSymbol(), position.getQuantity());
        } catch (Exception e) {
            logger.warn("归一化托管止损参数失败，回退本地止损: symbol={}, reason={}",
                    position.getSymbol(), e.getMessage());
            return false;
        }

        ExchangeStopState existing = exchangeStopStates.get(symbolKey);
        if (existing != null
                && existing.orderId() != null
                && existing.stopPrice().compareTo(normalizedStop) == 0
                && existing.quantity().compareTo(normalizedQty) == 0) {
            return true;
        }
        if (existing == null) {
            try {
                int cleared = stopCapableExchange.cancelReduceOnlyStopOrders(position.getSymbol());
                if (cleared > 0) {
                    logger.info("首次接管前清理历史 reduceOnly 止损单: symbol={}, count={}", position.getSymbol(), cleared);
                }
            } catch (Exception e) {
                logger.warn("清理历史 reduceOnly 止损单失败: symbol={}, reason={}", position.getSymbol(), e.getMessage());
            }
        }
        cancelExchangeStop(position.getSymbol());

        String clientOrderId = buildStopClientOrderId();
        try {
            String stopOrderId = stopCapableExchange.placeReduceOnlyStopMarketOrder(
                    position.getSymbol(),
                    closeSide,
                    normalizedStop,
                    normalizedQty,
                    clientOrderId
            );
            exchangeStopStates.put(symbolKey, new ExchangeStopState(stopOrderId, normalizedStop, normalizedQty));
            logger.info("托管止损已挂单: symbol={}, stop={}, qty={}, orderId={}",
                    position.getSymbol(), normalizedStop, normalizedQty, stopOrderId);
            return true;
        } catch (Exception e) {
            logger.warn("挂托管止损失败，回退本地止损: symbol={}, reason={}",
                    position.getSymbol(), e.getMessage());
            return false;
        }
    }

    private void cancelExchangeStop(Symbol symbol) {
        if (symbol == null) {
            return;
        }
        String symbolKey = symbol.toPairString();
        ExchangeStopState state = exchangeStopStates.remove(symbolKey);
        String stopOrderId = state == null ? null : state.orderId();
        if (stopOrderId == null || stopOrderId.isBlank()) {
            return;
        }
        try {
            exchange.cancelOrder(stopOrderId, symbol);
            logger.info("已撤销托管止损单: symbol={}, orderId={}", symbol, stopOrderId);
        } catch (Exception e) {
            logger.warn("撤销托管止损单失败，将由后续同步重试: symbol={}, orderId={}, reason={}",
                    symbol, stopOrderId, e.getMessage());
        }
    }

    private String buildStopClientOrderId() {
        String id = "sl_" + UUID.randomUUID().toString().replace("-", "");
        if (id.length() > 36) {
            return id.substring(0, 36);
        }
        return id;
    }

    private String resolveStrategyIdForPosition(Position position) {
        String symbolKey = position.getSymbol().toPairString();
        String strategyId = trackedPositionStrategyIds.get(symbolKey);
        if (strategyId != null && !strategyId.isBlank()) {
            return strategyId;
        }
        for (Strategy strategy : strategyEngine.getStrategies()) {
            if (strategy.getSymbol().equals(position.getSymbol())) {
                return strategy.getStrategyId();
            }
        }
        return "STOP_LOSS";
    }

    private String resolveStrategyIdForSymbol(Symbol symbol) {
        for (Strategy strategy : strategyEngine.getStrategies()) {
            if (strategy.getSymbol().equals(symbol)) {
                return strategy.getStrategyId();
            }
        }
        return "ORPHAN_ADOPT";
    }

    public boolean isRunning() {
        return running;
    }
}
