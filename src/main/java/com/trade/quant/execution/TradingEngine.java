package com.trade.quant.execution;

import com.trade.quant.backtest.BacktestTradeListener;
import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ProtectiveStopCapableExchange;
import com.trade.quant.execution.notifier.NetworkAlertNotifier;
import com.trade.quant.execution.notifier.NetworkAlertNotifierFactory;
import com.trade.quant.execution.notifier.NoopNetworkAlertNotifier;
import com.trade.quant.execution.notifier.NoopTradeFillNotifier;
import com.trade.quant.execution.notifier.TradeFillNotifier;
import com.trade.quant.execution.notifier.TradeFillNotifierFactory;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.EquityAwareStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.Strategy;
import com.trade.quant.strategy.StrategyEngine;
import com.trade.quant.strategy.TradeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final Set<String> closeCallbackNotifiedSymbols;
    private final boolean orphanPositionAutoAdoptEnabled;
    private final BigDecimal orphanPositionStopLossPercent;
    private final boolean liveEntryRepriceEnabled;
    private final StrategyEngine.SignalListener signalListener;
    private final NetworkAlertNotifier networkAlertNotifier;
    private final TradeFillNotifier tradeFillNotifier;
    private ScheduledExecutorService positionSyncExecutor;
    private volatile long lastPositionSyncTransientLogAtMillis;
    private boolean running;

    private record PendingEntryContext(String strategyId, BigDecimal stopDistance, PositionSide side, long createdAtMillis) {}
    private record ExchangeStopState(String orderId, BigDecimal stopPrice, BigDecimal quantity) {}

    public TradingEngine(StrategyEngine strategyEngine, RiskControl riskControl,
                         OrderExecutor orderExecutor, StopLossManager stopLossManager,
                         Exchange exchange) {
        this(strategyEngine, riskControl, orderExecutor, stopLossManager, exchange,
                NetworkAlertNotifierFactory.fromConfig(ConfigManager.getInstance(), exchange.getName()),
                TradeFillNotifierFactory.fromConfig(ConfigManager.getInstance(), exchange.getName()));
    }

    public TradingEngine(StrategyEngine strategyEngine, RiskControl riskControl,
                         OrderExecutor orderExecutor, StopLossManager stopLossManager,
                         Exchange exchange, NetworkAlertNotifier networkAlertNotifier) {
        this(strategyEngine, riskControl, orderExecutor, stopLossManager, exchange,
                networkAlertNotifier,
                TradeFillNotifierFactory.fromConfig(ConfigManager.getInstance(), exchange.getName()));
    }

    public TradingEngine(StrategyEngine strategyEngine, RiskControl riskControl,
                         OrderExecutor orderExecutor, StopLossManager stopLossManager,
                         Exchange exchange, NetworkAlertNotifier networkAlertNotifier,
                         TradeFillNotifier tradeFillNotifier) {
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
        this.closeCallbackNotifiedSymbols = ConcurrentHashMap.newKeySet();
        ConfigManager cfg = ConfigManager.getInstance();
        this.orphanPositionAutoAdoptEnabled = cfg.getBooleanProperty("live.orphan.position.adopt", true);
        this.orphanPositionStopLossPercent = cfg.getBigDecimalProperty(
                "live.orphan.position.stop.loss.percent",
                new BigDecimal("0.02")
        );
        this.liveEntryRepriceEnabled = cfg.getBooleanProperty("live.entry.reprice.enabled", true);
        this.signalListener = this::onSignal;
        this.networkAlertNotifier = networkAlertNotifier == null ? NoopNetworkAlertNotifier.INSTANCE : networkAlertNotifier;
        this.tradeFillNotifier = tradeFillNotifier == null ? NoopTradeFillNotifier.INSTANCE : tradeFillNotifier;
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
        try {
            networkAlertNotifier.close();
        } catch (Exception e) {
            logger.warn("Close network alert notifier failed: {}", e.getMessage());
        }
        try {
            tradeFillNotifier.close();
        } catch (Exception e) {
            logger.warn("Close trade fill notifier failed: {}", e.getMessage());
        }

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

            Signal effectiveSignal = maybeRepriceEntrySignal(signal);

            AccountInfo info = null;
            try {
                info = exchange.getAccountInfo();
                riskControl.updateAccountInfo(info);
                updateStrategyEquity(info);
            } catch (Exception e) {
                    logger.warn("Position sync temporarily unavailable, will retry: {}", e.getMessage());
                    notifyExchangeUnavailable("onSignal.account_info", e);
            }

            List<Position> positions = exchange.getOpenPositions(effectiveSignal.getSymbol());
            Order order = riskControl.validateAndCreateOrder(effectiveSignal, positions);
            if (order == null) {
                logger.info("Order rejected by risk control");
                return;
            }

            String orderId = orderExecutor.submitOrder(order);
            logger.info("订单已提交: {}", orderId);

            if (effectiveSignal.isEntry()) {
                handleEntrySignal(effectiveSignal, order, orderId);
            } else if (effectiveSignal.isExit()) {
                handleExitSignal(effectiveSignal, order, orderId);
            }

        } catch (Exception e) {
            logger.error("Signal handling failed: {}", e.getMessage(), e);
        }
    }

    private void handleEntrySignal(Signal signal, Order order, String orderId) {
        String symbolKey = signal.getSymbol().toPairString();
        PositionSide entrySide = signal.getSide() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
        BigDecimal stopDistance = resolveStopDistance(signal.getPrice(), signal.getStopLoss());
        pendingEntryContexts.put(symbolKey,
                new PendingEntryContext(signal.getStrategyId(), stopDistance, entrySide, System.currentTimeMillis()));

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
                notifyExchangeUnavailable("handleEntrySignal.open_positions", e);
            }
        }

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("开仓订单未确认成交，跳过本地持仓接管: symbol={}, orderId={}", signal.getSymbol(), orderId);
            return;
        }

        BigDecimal effectiveStopLoss = resolveInitialStopLoss(signal, entryPrice);
        Position pos = new Position(
                signal.getSymbol(),
                entrySide,
                entryPrice,
                quantity,
                effectiveStopLoss,
                BigDecimal.ONE
        );
        armStopProtection(pos);
        trackedPositions.put(symbolKey, pos);
        trackedPositionStrategyIds.put(symbolKey, signal.getStrategyId());
        pendingEntryContexts.remove(symbolKey);
        syncStrategyPositions();
        notifyPositionOpened(signal, pos);
        notifyEntryFill(signal, order, orderId, executed, entryPrice, quantity);
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
            notifyExitFill(signal, order, orderId, executed, exitPrice, actualQty, pnl, strategyId);
            boolean fullyClosed = actualQty.compareTo(tracked.getQuantity()) >= 0;
            if (fullyClosed) {
                ExitReason reason = signal.getExitReason() != null ? signal.getExitReason() : ExitReason.STRATEGY_EXIT;
                notifyPositionClosed(symbolKey, strategyId, tracked, exitPrice, actualQty, pnl, reason);
            }
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
        notifyExchangeUnavailable("fetchExecutedOrder", lastException);
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
                    notifyExchangeUnavailable("updatePositions.transient", e);
                    return;
                }
                notifyExchangeUnavailable("updatePositions", e);
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

        BigDecimal adoptedStop = resolveStopLossFromDistance(
                matched.getEntryPrice(),
                context.stopDistance(),
                matched.getSide()
        );
        if (adoptedStop.compareTo(BigDecimal.ZERO) <= 0) {
            adoptedStop = buildFallbackStopLoss(matched);
        }
        if (adoptedStop.compareTo(BigDecimal.ZERO) <= 0) {
            pendingEntryContexts.remove(symbolKey);
            logger.warn("寮€浠撳緟鎺ョ姝㈡崯璁＄畻澶辫触锛屾斁寮冩帴绠? symbol={}", symbol);
            return false;
        }

        Position adopted = new Position(
                symbol,
                matched.getSide(),
                matched.getEntryPrice(),
                matched.getQuantity(),
                adoptedStop,
                matched.getLeverage()
        );

        trackedPositions.put(symbolKey, adopted);
        trackedPositionStrategyIds.put(symbolKey, context.strategyId());
        pendingEntryContexts.remove(symbolKey);

        armStopProtection(adopted);
        notifyPositionOpened(buildSyntheticEntrySignal(context.strategyId(), adopted), adopted);

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
            notifyExchangeUnavailable("reconcileTrackedPosition", e);
        }
    }

    private void removeTrackedPosition(String symbolKey, Symbol symbol) {
        cancelExchangeStop(symbol);
        Position removed = trackedPositions.remove(symbolKey);
        String strategyId = trackedPositionStrategyIds.remove(symbolKey);
        boolean alreadyNotified = closeCallbackNotifiedSymbols.remove(symbolKey);
        if (!alreadyNotified && removed != null && removed.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            notifyPositionClosed(
                    symbolKey,
                    strategyId,
                    removed,
                    removed.getEntryPrice(),
                    removed.getQuantity(),
                    BigDecimal.ZERO,
                    ExitReason.FORCE_CLOSE
            );
        }
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

    private Signal maybeRepriceEntrySignal(Signal signal) {
        if (signal == null || !signal.isEntry() || !liveEntryRepriceEnabled) {
            return signal;
        }
        BigDecimal signalPrice = signal.getPrice();
        BigDecimal signalStop = signal.getStopLoss();
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) <= 0
                || signalStop == null || signalStop.compareTo(BigDecimal.ZERO) <= 0) {
            return signal;
        }
        try {
            Ticker ticker = exchange.getTicker(signal.getSymbol());
            BigDecimal repricedEntry = resolveLiveEntryReferencePrice(signal.getSide(), ticker, signalPrice);
            if (repricedEntry == null || repricedEntry.compareTo(BigDecimal.ZERO) <= 0) {
                return signal;
            }
            BigDecimal stopDistance = signalPrice.subtract(signalStop).abs();
            if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
                return signal;
            }
            BigDecimal repricedStop = signal.getSide() == Side.BUY
                    ? repricedEntry.subtract(stopDistance)
                    : repricedEntry.add(stopDistance);
            if (repricedStop.compareTo(BigDecimal.ZERO) <= 0) {
                return signal;
            }
            BigDecimal normalizedEntry = Decimal.scalePrice(repricedEntry);
            BigDecimal normalizedStop = Decimal.scalePrice(repricedStop);
            Signal repricedSignal = new Signal(
                    signal.getStrategyId(),
                    signal.getSymbol(),
                    signal.getType(),
                    signal.getSide(),
                    normalizedEntry,
                    signal.getQuantity(),
                    normalizedStop,
                    signal.getTakeProfit(),
                    signal.getReason(),
                    signal.getMetrics(),
                    signal.getExitReason(),
                    signal.isMaker(),
                    signal.getDelayBars(),
                    signal.isUseSignalPrice(),
                    signal.isSkipFriction(),
                    signal.isEngineStopLossEnabled()
            );
            if (normalizedEntry.compareTo(signalPrice) != 0 || normalizedStop.compareTo(signalStop) != 0) {
                logger.info("Live entry signal repriced: symbol={}, side={}, signalPrice={}, repricedPrice={}, repricedStop={}",
                        signal.getSymbol(), signal.getSide(), signalPrice, normalizedEntry, normalizedStop);
            }
            return repricedSignal;
        } catch (Exception e) {
            logger.warn("Live entry repricing failed, fallback to strategy signal: symbol={}, reason={}",
                    signal.getSymbol(), e.getMessage());
            notifyExchangeUnavailable("entry_reprice_ticker", e);
            return signal;
        }
    }

    private BigDecimal resolveLiveEntryReferencePrice(Side side, Ticker ticker, BigDecimal fallbackPrice) {
        if (side == Side.BUY) {
            return firstPositivePrice(
                    ticker == null ? null : ticker.getAskPrice(),
                    ticker == null ? null : ticker.getLastPrice(),
                    fallbackPrice
            );
        }
        return firstPositivePrice(
                ticker == null ? null : ticker.getBidPrice(),
                ticker == null ? null : ticker.getLastPrice(),
                fallbackPrice
        );
    }

    private BigDecimal firstPositivePrice(BigDecimal... candidates) {
        if (candidates == null) {
            return null;
        }
        for (BigDecimal candidate : candidates) {
            if (candidate != null && candidate.compareTo(BigDecimal.ZERO) > 0) {
                return candidate;
            }
        }
        return null;
    }

    private BigDecimal resolveInitialStopLoss(Signal signal, BigDecimal entryPrice) {
        BigDecimal fallbackStop = signal == null ? BigDecimal.ZERO : signal.getStopLoss();
        if (signal == null || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackStop;
        }
        BigDecimal signalPrice = signal.getPrice();
        BigDecimal signalStop = signal.getStopLoss();
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) <= 0
                || signalStop == null || signalStop.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackStop;
        }
        BigDecimal stopDistance = signalPrice.subtract(signalStop).abs();
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackStop;
        }
        BigDecimal adjustedStop = signal.getSide() == Side.BUY
                ? entryPrice.subtract(stopDistance)
                : entryPrice.add(stopDistance);
        if (adjustedStop.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackStop;
        }
        return Decimal.scalePrice(adjustedStop);
    }

    private BigDecimal resolveStopDistance(BigDecimal entryPrice, BigDecimal stopLoss) {
        if (entryPrice == null || stopLoss == null) {
            return BigDecimal.ZERO;
        }
        if (entryPrice.compareTo(BigDecimal.ZERO) <= 0 || stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return entryPrice.subtract(stopLoss).abs();
    }

    private BigDecimal resolveStopLossFromDistance(BigDecimal entryPrice, BigDecimal stopDistance, PositionSide side) {
        if (entryPrice == null || stopDistance == null || side == null) {
            return BigDecimal.ZERO;
        }
        if (entryPrice.compareTo(BigDecimal.ZERO) <= 0 || stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stop = side == PositionSide.LONG
                ? entryPrice.subtract(stopDistance)
                : entryPrice.add(stopDistance);
        if (stop.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return Decimal.scalePrice(stop);
    }

    private void notifyPositionOpened(Signal signal, Position position) {
        if (signal == null || position == null) {
            return;
        }
        Strategy strategy = resolveStrategy(signal.getStrategyId(), position.getSymbol());
        if (!(strategy instanceof BacktestTradeListener listener)) {
            return;
        }
        try {
            KLine snapshot = buildSyntheticKLine(position.getSymbol(), strategy.getInterval(), position.getEntryPrice());
            listener.onPositionOpened(position, signal, snapshot, signal.getMetrics());
        } catch (Exception e) {
            logger.warn("Notify strategy position-opened failed: strategyId={}, symbol={}, reason={}",
                    signal.getStrategyId(), position.getSymbol(), e.getMessage());
        }
    }

    private void notifyPositionClosed(String symbolKey,
                                      String strategyId,
                                      Position position,
                                      BigDecimal exitPrice,
                                      BigDecimal quantity,
                                      BigDecimal pnl,
                                      ExitReason reason) {
        if (symbolKey == null || position == null) {
            return;
        }
        Strategy strategy = resolveStrategy(strategyId, position.getSymbol());
        if (!(strategy instanceof BacktestTradeListener listener)) {
            return;
        }
        BigDecimal closedQty = quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0
                ? quantity
                : position.getQuantity();
        if (closedQty == null || closedQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal effectiveExit = exitPrice != null && exitPrice.compareTo(BigDecimal.ZERO) > 0
                ? exitPrice
                : position.getEntryPrice();
        BigDecimal effectivePnl = pnl == null ? BigDecimal.ZERO : pnl;
        ClosedTrade trade = new ClosedTrade(
                UUID.randomUUID().toString(),
                position.getSymbol(),
                position.getSide(),
                position.getEntryPrice(),
                effectiveExit,
                closedQty,
                effectivePnl,
                BigDecimal.ZERO,
                position.getOpenTime(),
                Instant.now(),
                strategyId != null && !strategyId.isBlank() ? strategyId : strategy.getStrategyId()
        );
        ExitReason exitReason = reason == null ? ExitReason.FORCE_CLOSE : reason;
        try {
            listener.onPositionClosed(trade, exitReason, (TradeMetrics) null);
            closeCallbackNotifiedSymbols.add(symbolKey);
        } catch (Exception e) {
            logger.warn("Notify strategy position-closed failed: strategyId={}, symbol={}, reason={}",
                    strategyId, position.getSymbol(), e.getMessage());
        }
    }

    private Strategy resolveStrategy(String strategyId, Symbol symbol) {
        Strategy symbolMatch = null;
        for (Strategy strategy : strategyEngine.getStrategies()) {
            if (strategyId != null && strategyId.equals(strategy.getStrategyId())) {
                return strategy;
            }
            if (symbolMatch == null && strategy.getSymbol().equals(symbol)) {
                symbolMatch = strategy;
            }
        }
        return symbolMatch;
    }

    private Signal buildSyntheticEntrySignal(String strategyId, Position position) {
        if (position == null) {
            return null;
        }
        SignalType type = position.getSide() == PositionSide.LONG ? SignalType.ENTRY_LONG : SignalType.ENTRY_SHORT;
        Side side = position.getSide() == PositionSide.LONG ? Side.BUY : Side.SELL;
        return new Signal(
                strategyId,
                position.getSymbol(),
                type,
                side,
                position.getEntryPrice(),
                position.getQuantity(),
                position.getStopLoss(),
                BigDecimal.ZERO,
                "adopt_pending_entry"
        );
    }

    private KLine buildSyntheticKLine(Symbol symbol, Interval interval, BigDecimal price) {
        Interval effectiveInterval = interval == null ? Interval.FIFTEEN_MINUTES : interval;
        BigDecimal p = price != null && price.compareTo(BigDecimal.ZERO) > 0
                ? Decimal.scalePrice(price)
                : BigDecimal.ZERO;
        Instant openTime = Instant.now();
        Instant closeTime = openTime.plus(effectiveInterval.getMinutes(), ChronoUnit.MINUTES).minusMillis(1);
        return new KLine(
                symbol,
                effectiveInterval,
                openTime,
                closeTime,
                p,
                p,
                p,
                p,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L
        );
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
                notifyExitFill(
                        strategyId,
                        position.getSymbol(),
                        side,
                        closeOrder,
                        orderId,
                        executed,
                        exitPrice,
                        exitQty,
                        pnl
                );
                boolean fullyClosed = exitQty.compareTo(position.getQuantity()) >= 0;
                if (fullyClosed) {
                    notifyPositionClosed(
                            position.getSymbol().toPairString(),
                            strategyId,
                            position,
                            exitPrice,
                            exitQty,
                            pnl,
                            ExitReason.STOP_LOSS
                    );
                }
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
            notifyExchangeUnavailable("closePosition", e);
        }
    }

    private void notifyExchangeUnavailable(String scene, Throwable error) {
        try {
            networkAlertNotifier.notifyExchangeUnavailable(scene, error);
        } catch (Exception e) {
            logger.warn("Notify exchange unavailable failed: {}", e.getMessage());
        }
    }

    private void notifyEntryFill(Signal signal,
                                 Order submittedOrder,
                                 String submittedOrderId,
                                 Order executedOrder,
                                 BigDecimal avgFillPrice,
                                 BigDecimal filledQuantity) {
        if (signal == null) {
            return;
        }
        try {
            String fillEventId = buildFillEventId(
                    "entry",
                    signal.getSymbol(),
                    signal.getSide(),
                    submittedOrder,
                    submittedOrderId,
                    executedOrder,
                    avgFillPrice,
                    filledQuantity
            );
            tradeFillNotifier.notifyEntryFilled(
                    fillEventId,
                    signal.getStrategyId(),
                    signal.getSymbol(),
                    signal.getSide(),
                    avgFillPrice,
                    filledQuantity
            );
        } catch (Exception e) {
            logger.warn("Notify entry fill failed: strategyId={}, symbol={}, reason={}",
                    signal.getStrategyId(), signal.getSymbol(), e.getMessage());
        }
    }

    private void notifyExitFill(Signal signal,
                                Order submittedOrder,
                                String submittedOrderId,
                                Order executedOrder,
                                BigDecimal avgFillPrice,
                                BigDecimal filledQuantity,
                                BigDecimal pnl,
                                String strategyId) {
        if (signal == null) {
            return;
        }
        notifyExitFill(
                strategyId,
                signal.getSymbol(),
                signal.getSide(),
                submittedOrder,
                submittedOrderId,
                executedOrder,
                avgFillPrice,
                filledQuantity,
                pnl
        );
    }

    private void notifyExitFill(String strategyId,
                                Symbol symbol,
                                Side side,
                                Order submittedOrder,
                                String submittedOrderId,
                                Order executedOrder,
                                BigDecimal avgFillPrice,
                                BigDecimal filledQuantity,
                                BigDecimal pnl) {
        try {
            String fillEventId = buildFillEventId(
                    "exit",
                    symbol,
                    side,
                    submittedOrder,
                    submittedOrderId,
                    executedOrder,
                    avgFillPrice,
                    filledQuantity
            );
            tradeFillNotifier.notifyExitFilled(
                    fillEventId,
                    strategyId,
                    symbol,
                    side,
                    avgFillPrice,
                    filledQuantity,
                    pnl
            );
        } catch (Exception e) {
            logger.warn("Notify exit fill failed: strategyId={}, symbol={}, reason={}",
                    strategyId, symbol, e.getMessage());
        }
    }

    private String buildFillEventId(String phase,
                                    Symbol symbol,
                                    Side side,
                                    Order submittedOrder,
                                    String submittedOrderId,
                                    Order executedOrder,
                                    BigDecimal avgFillPrice,
                                    BigDecimal filledQuantity) {
        String orderKey = firstNonBlank(
                executedOrder == null ? null : executedOrder.getExchangeOrderId(),
                submittedOrder == null ? null : submittedOrder.getExchangeOrderId(),
                executedOrder == null ? null : executedOrder.getClientOrderId(),
                submittedOrder == null ? null : submittedOrder.getClientOrderId(),
                executedOrder == null ? null : executedOrder.getOrderId(),
                submittedOrder == null ? null : submittedOrder.getOrderId(),
                submittedOrderId
        );
        if (orderKey == null) {
            orderKey = "unknown";
        }
        BigDecimal normalizedPrice = avgFillPrice == null ? BigDecimal.ZERO : Decimal.scalePrice(avgFillPrice);
        BigDecimal normalizedQty = filledQuantity == null ? BigDecimal.ZERO : Decimal.scaleQuantity(filledQuantity);
        String symbolKey = symbol == null ? "unknown" : symbol.toPairString();
        String sideKey = side == null ? "UNKNOWN" : side.name();
        return phase + "|" + symbolKey + "|" + sideKey + "|" + orderKey + "|"
                + normalizedPrice.toPlainString() + "|" + normalizedQty.toPlainString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
