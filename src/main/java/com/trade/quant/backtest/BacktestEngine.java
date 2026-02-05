package com.trade.quant.backtest;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.Strategy;
import com.trade.quant.strategy.TradeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 回测引擎
 *
 * 职责：
 * 1. 模拟历史交易
 * 2. 计算手续费和滑点
 * 3. 生成资金曲线
 * 4. 输出回测报告
 */
public class BacktestEngine {
    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);
    private static final int ORDER_DELAY_BARS = 1;
    private final BacktestConfig config;
    private final Exchange exchange;
    private final Strategy strategy;
    private final BacktestTradeLogger tradeLogger;
    private final Map<Position, TradeMetrics> tradeMetrics;
    private final Map<Position, BigDecimal> entryFees;
    private final Map<Position, Boolean> engineStopLossEnabled;
    private final List<KLine> preloadedKLines;
    private final List<PendingOrder> pendingOrders;
    private final int limitOrderMaxBars;
    private BigDecimal balance;
    private final List<ClosedTrade> closedTrades;  // 已平仓交易记录（包含完整盈亏信息）
    private final List<Position> positions;
    private final List<BigDecimal> equityCurve;
    public BacktestEngine(BacktestConfig config, Exchange exchange, Strategy strategy) {
        this(config, exchange, strategy, null, null);
    }

    public BacktestEngine(BacktestConfig config, Exchange exchange, Strategy strategy, BacktestTradeLogger tradeLogger) {
        this(config, exchange, strategy, tradeLogger, null);
    }

    public BacktestEngine(BacktestConfig config,
                          Exchange exchange,
                          Strategy strategy,
                          BacktestTradeLogger tradeLogger,
                          List<KLine> preloadedKLines) {
        this.config = config;
        this.exchange = exchange;
        this.strategy = strategy;
        this.tradeLogger = tradeLogger;
        this.preloadedKLines = preloadedKLines;
        this.balance = config.getInitialCapital();
        this.closedTrades = new ArrayList<>();
        this.positions = new ArrayList<>();
        this.equityCurve = new ArrayList<>();
        this.tradeMetrics = new HashMap<>();
        this.entryFees = new HashMap<>();
        this.engineStopLossEnabled = new HashMap<>();
        this.pendingOrders = new ArrayList<>();
        this.limitOrderMaxBars = config.getLimitOrderMaxBars();
        this.equityCurve.add(balance);
    }

    private static class PendingOrder {
        private final String id;
        private final Signal signal;
        private final Position position;
        private final boolean entry;
        private final boolean market;
        private final int createdIndex;
        private PendingOrder(Signal signal, Position position, boolean entry, int createdIndex) {
            this.id = UUID.randomUUID().toString();
            this.signal = signal;
            this.position = position;
            this.entry = entry;
            this.market = !signal.isMaker()
                    || signal.getPrice() == null
                    || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0;
            this.createdIndex = createdIndex;
        }
    }

    /**
     * 运行回测
     */
    public BacktestResult run() throws Exception {
        logger.info("开始回测: {} {} {} - {}",
                config.getSymbol(), config.getInterval(),
                config.getStartTime(), config.getEndTime());
        // 获取历史数据
        List<KLine> kLines = fetchHistoricalData();
        logger.info("加载K线数据: {} 条", kLines.size());
        // 初始化策略
        strategy.reset();
        // 逐K线模拟
        for (int i = 0; i < kLines.size(); i++) {
            KLine kLine = kLines.get(i);
            // 更新策略的K线计数
            if (strategy instanceof com.trade.quant.strategy.AbstractStrategy) {
                ((com.trade.quant.strategy.AbstractStrategy) strategy).incrementBarCount();
            }
            // 获取当前可用的K线数据
            List<KLine> availableKLines = new ArrayList<>(kLines.subList(0, i + 1));
            // pending entry orders first
            processPendingEntryOrders(i, kLine);
            // stop-loss checks
            checkStopLoss(kLine);
            // pending exit orders
            processPendingExitOrders(i, kLine);
            // strategy-driven exit signals
            checkPositionUpdates(kLine, availableKLines, i);
            // strategy entry/exit signals
            Signal signal = strategy.analyze(availableKLines);
            if (signal != null) {
                processSignal(signal, kLine, i);
            }
            // update equity
            updateEquity(kLine.getClose());
        }
        // 平掉所有持仓
        closeAllPositions(kLines.get(kLines.size() - 1).getClose());
        // 计算回测指标
        return calculateResults();
    }

    /**
     * 获取历史数据
     */
    private List<KLine> fetchHistoricalData() throws Exception {
        if (preloadedKLines != null && !preloadedKLines.isEmpty()) {
            return preloadedKLines.stream()
                    .filter(k -> !k.getOpenTime().isBefore(config.getStartTime()))
                    .filter(k -> !k.getCloseTime().isAfter(config.getEndTime()))
                    .toList();
        }
        List<KLine> allKLines = new ArrayList<>();
        long endTime = config.getEndTime().toEpochMilli();
        long startTime = config.getStartTime().toEpochMilli();
        while (endTime > startTime) {
            List<KLine> batch = exchange.getKLines(
                    config.getSymbol(),
                    config.getInterval(),
                    1000,
                    endTime
            );
            if (batch.isEmpty()) {
                break;
            }
            allKLines.addAll(0, batch);
            endTime = batch.get(0).getOpenTime().toEpochMilli() - 1;
        }
        // 过滤时间范围
        return allKLines.stream()
                .filter(k -> !k.getOpenTime().isBefore(config.getStartTime()))
                .filter(k -> !k.getCloseTime().isAfter(config.getEndTime()))
                .toList();
    }

    /**
     * 检查止损
     */
    private void checkStopLoss(KLine kLine) {
        Iterator<Position> it = positions.iterator();
        while (it.hasNext()) {
            Position pos = it.next();
            if (pos.isClosed()) {
                it.remove();
                continue;
            }
            Boolean engineStopEnabled = engineStopLossEnabled.get(pos);
            if (engineStopEnabled != null && !engineStopEnabled) {
                continue;
            }
            BigDecimal stopLoss = pos.getStopLoss();
            if (stopLoss == null || stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            boolean triggered = pos.getSide() == PositionSide.LONG
                    ? kLine.getLow().compareTo(stopLoss) <= 0
                    : kLine.getHigh().compareTo(stopLoss) >= 0;
            if (triggered) {
                BigDecimal basePrice = resolveStopMarketPrice(pos, kLine);
                BigDecimal exitPrice = applyExecutionFriction(basePrice, getExitSide(pos));
                closePosition(pos, exitPrice, pos.getQuantity(), kLine.getCloseTime(), ExitReason.STOP_LOSS, false);
                cancelPendingExitOrders(pos);
                if (pos.isClosed()) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 检查策略的持仓更新（支持动态出场信号）
     * @param kLine 当前K线
     * @param allKLines 完整的K线历史数据
     */
    private void checkPositionUpdates(KLine kLine, List<KLine> allKLines, int barIndex) {
        for (Position pos : positions) {
            if (pos.isClosed()) {
                continue;
            }
            Signal exitSignal = strategy.onPositionUpdate(pos, kLine, allKLines);
            if (exitSignal != null && exitSignal.isExit()) {
                if (isImmediateSignal(exitSignal)) {
                    executeImmediateExit(pos, exitSignal, kLine);
                } else {
                    queueExitOrder(pos, exitSignal, barIndex, kLine);
                }
                break;
            }
        }
    }

    /**
     * 处理信号
     */
    private void processSignal(Signal signal, KLine kLine, int barIndex) {
        if (signal.isEntry()) {
            queueEntryOrder(signal, barIndex, kLine);
        } else if (signal.isExit()) {
            queueExitOrder(signal, barIndex, kLine);
        }
    }

    private void queueEntryOrder(Signal signal, int barIndex, KLine kLine) {
        BigDecimal qty = signal.getQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal stopLoss = signal.getStopLoss();
            if (stopLoss == null || stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
                return; // 无止损无法按固定风险仓位计算
            }
        }
        if (hasOpenPosition(signal.getSymbol()) || hasPendingEntry(signal.getSymbol())) {
            return;
        }
        if (isImmediateSignal(signal)) {
            executeImmediateEntry(signal, kLine);
            return;
        }
        pendingOrders.add(new PendingOrder(signal, null, true, barIndex));
    }

    private void queueExitOrder(Signal signal, int barIndex, KLine kLine) {
        Position pos = findOpenPosition(signal.getSymbol());
        if (pos == null || pos.isClosed()) {
            return;
        }
        queueExitOrder(pos, signal, barIndex, kLine);
    }

    private void queueExitOrder(Position pos, Signal signal, int barIndex, KLine kLine) {
        cancelPendingExitOrders(pos);
        if (isImmediateSignal(signal)) {
            executeImmediateExit(pos, signal, kLine);
            return;
        }
        pendingOrders.add(new PendingOrder(signal, pos, false, barIndex));
    }

    private boolean isImmediateSignal(Signal signal) {
        Integer delay = signal.getDelayBars();
        return delay != null && delay <= 0;
    }

    private void executeImmediateEntry(Signal signal, KLine kLine) {
        PendingOrder order = new PendingOrder(signal, null, true, 0);
        if (order.market) {
            fillEntryMarket(order, kLine);
        } else {
            fillEntryLimit(order, kLine);
        }
    }

    private void executeImmediateExit(Position pos, Signal signal, KLine kLine) {
        cancelPendingExitOrders(pos);
        PendingOrder order = new PendingOrder(signal, pos, false, 0);
        if (order.market) {
            fillExitMarket(order, kLine);
        } else {
            fillExitLimit(order, kLine);
        }
    }

    private void processPendingEntryOrders(int barIndex, KLine kLine) {
        Iterator<PendingOrder> it = pendingOrders.iterator();
        while (it.hasNext()) {
            PendingOrder order = it.next();
            if (!order.entry) {
                continue;
            }
            if (!isOrderEligible(order, barIndex)) {
                continue;
            }
            if (isOrderExpired(order, barIndex)) {
                it.remove();
                continue;
            }
            if (hasOpenPosition(order.signal.getSymbol())) {
                it.remove();
                continue;
            }
            boolean filled = order.market
                    ? fillEntryMarket(order, kLine)
                    : fillEntryLimit(order, kLine);
            if (filled) {
                it.remove();
            }
        }
    }

    private void processPendingExitOrders(int barIndex, KLine kLine) {
        Iterator<PendingOrder> it = pendingOrders.iterator();
        while (it.hasNext()) {
            PendingOrder order = it.next();
            if (order.entry) {
                continue;
            }
            if (!isOrderEligible(order, barIndex)) {
                continue;
            }
            if (isOrderExpired(order, barIndex)) {
                it.remove();
                continue;
            }
            Position pos = order.position;
            if (pos == null || pos.isClosed()) {
                it.remove();
                continue;
            }
            boolean filled = order.market
                    ? fillExitMarket(order, kLine)
                    : fillExitLimit(order, kLine);
            if (filled) {
                it.remove();
                if (pos.isClosed()) {
                    positions.remove(pos);
                }
            }
        }
    }

    private boolean fillEntryMarket(PendingOrder order, KLine kLine) {
        BigDecimal basePrice = resolveMarketEntryPrice(order.signal, kLine);
        BigDecimal entryPrice = applyExecutionFriction(basePrice, order.signal.getSide(), order.signal.isSkipFriction());
        openPositionFromFill(order.signal, entryPrice, kLine, false);
        return true;
    }

    private boolean fillEntryLimit(PendingOrder order, KLine kLine) {
        BigDecimal limitPrice = order.signal.getPrice();
        if (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        boolean shouldFill = order.signal.getSide() == Side.BUY
                ? kLine.getLow().compareTo(limitPrice) <= 0
                : kLine.getHigh().compareTo(limitPrice) >= 0;
        if (!shouldFill) {
            return false;
        }
        BigDecimal entryPrice = applyExecutionFriction(limitPrice, order.signal.getSide(), order.signal.isSkipFriction());
        openPositionFromFill(order.signal, entryPrice, kLine, order.signal.isMaker());
        return true;
    }

    private boolean fillExitMarket(PendingOrder order, KLine kLine) {
        BigDecimal basePrice = resolveMarketExitPrice(order.signal, kLine);
        BigDecimal exitPrice = applyExecutionFriction(basePrice, order.signal.getSide(), order.signal.isSkipFriction());
        executeExit(order.position, order.signal, exitPrice, kLine, false);
        return true;
    }

    private boolean fillExitLimit(PendingOrder order, KLine kLine) {
        BigDecimal limitPrice = order.signal.getPrice();
        if (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        boolean shouldFill = order.signal.getSide() == Side.SELL
                ? kLine.getHigh().compareTo(limitPrice) >= 0
                : kLine.getLow().compareTo(limitPrice) <= 0;
        if (!shouldFill) {
            return false;
        }
        BigDecimal exitPrice = applyExecutionFriction(limitPrice, order.signal.getSide(), order.signal.isSkipFriction());
        executeExit(order.position, order.signal, exitPrice, kLine, order.signal.isMaker());
        return true;
    }

    private void openPositionFromFill(Signal signal, BigDecimal entryPrice, KLine kLine, boolean makerFee) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal requestedQty = signal.getQuantity();
        BigDecimal quantity = (requestedQty == null || requestedQty.compareTo(BigDecimal.ZERO) <= 0)
                ? calculateRiskQuantity(signal, entryPrice, kLine)
                : calculateQuantity(requestedQty, entryPrice);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        PositionSide side = signal.getSide() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
        BigDecimal stopLoss = adjustStopLossForEntry(signal, entryPrice);
        Position position = new Position(
                signal.getSymbol(),
                side,
                entryPrice,
                quantity,
                stopLoss,
                config.getLeverage()
        );
        positions.add(position);
        engineStopLossEnabled.put(position, signal.isEngineStopLossEnabled());
        BigDecimal entryFee = calculateFee(entryPrice, quantity, makerFee);
        balance = balance.subtract(entryFee);
        entryFees.put(position, entryFee);
        TradeMetrics metrics = signal.getMetrics();
        if (metrics != null) {
            tradeMetrics.put(position, metrics);
        }
        if (strategy instanceof BacktestTradeListener listener) {
            listener.onPositionOpened(position, signal, kLine, metrics);
        }
        logger.debug("entry: {} {} {} @ {}", signal.getSymbol(), side, quantity, entryPrice);
    }

    private void executeExit(Position pos, Signal signal, BigDecimal exitPrice, KLine kLine, boolean makerFee) {
        ExitReason reason = signal.getExitReason() != null ? signal.getExitReason() : ExitReason.STRATEGY_EXIT;
        BigDecimal requestedQty = signal.getQuantity();
        BigDecimal closeQty = requestedQty == null || requestedQty.compareTo(BigDecimal.ZERO) <= 0
                ? pos.getQuantity()
                : requestedQty.min(pos.getQuantity());
        closePosition(pos, exitPrice, closeQty, kLine.getCloseTime(), reason, makerFee);
    }

    private Position findOpenPosition(Symbol symbol) {
        for (Position pos : positions) {
            if (!pos.isClosed() && pos.getSymbol().equals(symbol)) {
                return pos;
            }
        }
        return null;
    }

    private boolean hasOpenPosition(Symbol symbol) {
        return findOpenPosition(symbol) != null;
    }

    private boolean hasPendingEntry(Symbol symbol) {
        for (PendingOrder order : pendingOrders) {
            if (order.entry && order.signal.getSymbol().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    private void cancelPendingExitOrders(Position pos) {
        pendingOrders.removeIf(order -> !order.entry && order.position == pos);
    }

    private boolean isOrderEligible(PendingOrder order, int barIndex) {
        int delay = ORDER_DELAY_BARS;
        Integer signalDelay = order.signal.getDelayBars();
        if (signalDelay != null) {
            delay = Math.max(0, signalDelay);
        }
        return barIndex - order.createdIndex >= delay;
    }

    private boolean isOrderExpired(PendingOrder order, int barIndex) {
        if (order.market || limitOrderMaxBars <= 0) {
            return false;
        }
        int age = barIndex - order.createdIndex;
        return age > limitOrderMaxBars;
    }

    private BigDecimal resolveMarketEntryPrice(Signal signal, KLine kLine) {
        BigDecimal ref = signal.getPrice();
        if (signal.isUseSignalPrice() && ref != null && ref.compareTo(BigDecimal.ZERO) > 0) {
            return ref;
        }
        return kLine.getOpen();
    }

    private BigDecimal resolveMarketExitPrice(Signal signal, KLine kLine) {
        BigDecimal open = kLine.getOpen();
        BigDecimal ref = signal.getPrice();
        if (signal.isUseSignalPrice() && ref != null && ref.compareTo(BigDecimal.ZERO) > 0) {
            return ref;
        }
        if (ref == null || ref.compareTo(BigDecimal.ZERO) <= 0) {
            return open;
        }
        if (signal.getSide() == Side.SELL) {
            return open.min(ref);
        }
        return open.max(ref);
    }

    private BigDecimal resolveStopMarketPrice(Position pos, KLine kLine) {
        BigDecimal stop = pos.getStopLoss();
        BigDecimal open = kLine.getOpen();
        if (pos.getSide() == PositionSide.LONG) {
            return open.min(stop);
        }
        return open.max(stop);
    }

    /**
     * 入场
     */

    /**
     * 出场
     */

    /**
     * 平仓
     */
    private void closePosition(Position pos, BigDecimal price, BigDecimal quantity, Instant time, ExitReason reason, boolean makerExit) {
        BigDecimal pnl = calculatePnL(pos, price, quantity);
        BigDecimal entryFee = allocateEntryFee(pos, quantity);
        BigDecimal exitFee = calculateFee(price, quantity, makerExit);
        BigDecimal totalFee = entryFee.add(exitFee);
        // 创建已平仓交易记录
        ClosedTrade closedTrade = new ClosedTrade(
                UUID.randomUUID().toString(),
                pos.getSymbol(),
                pos.getSide(),
                pos.getEntryPrice(),
                price,
                quantity,
                pnl,
                totalFee,
                pos.getOpenTime(),
                time,
                strategy.getStrategyId()
        );
        closedTrades.add(closedTrade);
        balance = balance.add(pnl).subtract(exitFee);
        TradeMetrics metrics = tradeMetrics.get(pos);
        if (tradeLogger != null) {
            tradeLogger.record(closedTrade, reason, metrics);
        }
        if (strategy instanceof BacktestTradeListener listener) {
            listener.onPositionClosed(closedTrade, reason, metrics);
        }
        pos.reduce(quantity);
        if (pos.isClosed()) {
            tradeMetrics.remove(pos);
            entryFees.remove(pos);
            engineStopLossEnabled.remove(pos);
        }
        logger.debug("平仓: {} 价格:{} 盈亏:{} 手续费:{}", pos.getSymbol(), price, pnl, totalFee);
    }

    /**
     * 平掉所有持仓
     */
    private void closeAllPositions(BigDecimal price) {
        for (Position pos : positions) {
            BigDecimal exitPrice = applyExecutionFriction(price, getExitSide(pos));
            closePosition(pos, exitPrice, pos.getQuantity(), Instant.now(), ExitReason.FORCE_CLOSE, false);
        }
        positions.clear();
        pendingOrders.clear();
    }

    /**
     * 计算盈亏
     */
    private BigDecimal calculatePnL(Position pos, BigDecimal exitPrice, BigDecimal quantity) {
        BigDecimal priceDiff;
        if (pos.getSide() == PositionSide.LONG) {
            priceDiff = exitPrice.subtract(pos.getEntryPrice());
        } else {
            priceDiff = pos.getEntryPrice().subtract(exitPrice);
        }
        return priceDiff.multiply(quantity);
    }

    /**
     * 计算手续费
     */
    private BigDecimal calculateFee(BigDecimal price, BigDecimal quantity, boolean maker) {
        BigDecimal value = price.multiply(quantity);
        return value.multiply(maker ? config.getMakerFee() : config.getTakerFee());
    }

    /**
     * 应用滑点
     */
    private BigDecimal applyExecutionFriction(BigDecimal price, Side side) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal adjusted = applySpread(price, side);
        BigDecimal slippage = adjusted.multiply(config.getSlippage());
        if (side == Side.BUY) {
            return adjusted.add(slippage);
        } else {
            return adjusted.subtract(slippage);
        }
    }

    private BigDecimal applyExecutionFriction(BigDecimal price, Side side, boolean skipFriction) {
        if (skipFriction) {
            return price == null ? BigDecimal.ZERO : price;
        }
        return applyExecutionFriction(price, side);
    }

    private BigDecimal applySpread(BigDecimal price, Side side) {
        BigDecimal spread = config.getSpread();
        if (spread == null || spread.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal halfSpread = spread.divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        BigDecimal spreadAmount = price.multiply(halfSpread);
        if (side == Side.BUY) {
            return price.add(spreadAmount);
        } else {
            return price.subtract(spreadAmount);
        }
    }

    private Side getExitSide(Position position) {
        return position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY;
    }

    /**
     * 计算仓位数量
     */
    private BigDecimal calculateQuantity(BigDecimal signalQuantity, BigDecimal price) {
        if (signalQuantity == null || signalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal maxAffordable = balance.multiply(BigDecimal.valueOf(0.95)).divide(price, 3, RoundingMode.DOWN);
        return signalQuantity.min(maxAffordable);
    }

    private BigDecimal calculateRiskQuantity(Signal signal, BigDecimal entryPrice, KLine kLine) {
        BigDecimal stopDist = resolveStopDistance(signal, entryPrice);
        if (stopDist == null || stopDist.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskPerTrade = strategy.getConfig() != null ? strategy.getConfig().getRiskPerTrade() : BigDecimal.ZERO;
        if (riskPerTrade == null || riskPerTrade.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal equity = calculateEquityAtPrice(kLine != null ? kLine.getClose() : entryPrice);
        BigDecimal riskAmount = riskPerTrade.compareTo(BigDecimal.ONE) <= 0
                ? equity.multiply(riskPerTrade)
                : riskPerTrade;
        if (riskAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = riskAmount.divide(stopDist, 6, RoundingMode.DOWN);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal maxAffordable = balance.multiply(BigDecimal.valueOf(0.95)).divide(entryPrice, 6, RoundingMode.DOWN);
        return qty.min(maxAffordable);
    }

    private BigDecimal resolveStopDistance(Signal signal, BigDecimal entryPrice) {
        BigDecimal stopLoss = signal.getStopLoss();
        if (stopLoss == null || stopLoss.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal basePrice = entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0
                ? entryPrice
                : signal.getPrice();
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return basePrice.subtract(stopLoss).abs();
    }

    private BigDecimal adjustStopLossForEntry(Signal signal, BigDecimal entryPrice) {
        // Keep original stop-loss price to reflect real slippage risk (plan B).
        return signal.getStopLoss();
    }

    private BigDecimal calculateEquityAtPrice(BigDecimal markPrice) {
        BigDecimal equity = balance;
        if (markPrice == null) {
            return equity;
        }
        for (Position pos : positions) {
            pos.updateUnrealizedPnl(markPrice);
            equity = equity.add(pos.getUnrealizedPnl());
        }
        return equity;
    }

    private BigDecimal allocateEntryFee(Position pos, BigDecimal quantity) {
        BigDecimal remainingFee = entryFees.getOrDefault(pos, BigDecimal.ZERO);
        if (remainingFee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal currentQty = pos.getQuantity();
        if (currentQty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal feePortion = remainingFee.multiply(quantity)
                .divide(currentQty, 8, RoundingMode.HALF_UP);
        BigDecimal newRemaining = remainingFee.subtract(feePortion);
        if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
            newRemaining = BigDecimal.ZERO;
        }
        entryFees.put(pos, newRemaining);
        return feePortion;
    }

    /**
     * 更新权益曲线
     */
    private void updateEquity(BigDecimal currentPrice) {
        BigDecimal equity = balance;
        // 加上未实现盈亏
        for (Position pos : positions) {
            pos.updateUnrealizedPnl(currentPrice);
            equity = equity.add(pos.getUnrealizedPnl());
        }
        equityCurve.add(equity);
    }

    /**
     * 计算回测结果
     */
    private BacktestResult calculateResults() {
        BigDecimal finalBalance = equityCurve.get(equityCurve.size() - 1);
        BigDecimal totalReturn = finalBalance.subtract(config.getInitialCapital())
                .divide(config.getInitialCapital(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        // 计算最大回撤
        BigDecimal maxDrawdown = calculateMaxDrawdown();
        // 计算最大盈利
        BigDecimal maxProfit = calculateMaxProfit();
        // 计算年化收益率（复合年化收益率 CAGR）
        // CAGR = (final_value / initial_value)^(365/days) - 1
        long days = ChronoUnit.DAYS.between(config.getStartTime(), config.getEndTime());
        BigDecimal annualizedReturn = BigDecimal.ZERO;
        if (days > 0 && finalBalance.compareTo(BigDecimal.ZERO) > 0
                && config.getInitialCapital().compareTo(BigDecimal.ZERO) > 0) {
            double ratio = finalBalance.doubleValue() / config.getInitialCapital().doubleValue();
            double yearsExponent = 365.0 / days;
            double cagr = Math.pow(ratio, yearsExponent) - 1;
            annualizedReturn = BigDecimal.valueOf(cagr * 100).setScale(4, RoundingMode.HALF_UP);
        }
        // 计算夏普比率（简化版，无风险利率设为0）
        BigDecimal sharpeRatio = calculateSharpeRatio();
        // 使用 closedTrades 进行统计（包含真实的盈亏数据）
        if (closedTrades.isEmpty()) {
            return new BacktestResult(
                    totalReturn, annualizedReturn, maxDrawdown, maxProfit, sharpeRatio,
                    0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, equityCurve
            );
        }
        // 统计盈亏交易
        List<BigDecimal> pnlList = closedTrades.stream()
                .map(ClosedTrade::getNetPnl)
                .toList();
        int winningTrades = (int) pnlList.stream().filter(p -> p.compareTo(BigDecimal.ZERO) > 0).count();
        int losingTrades = (int) pnlList.stream().filter(p -> p.compareTo(BigDecimal.ZERO) < 0).count();
        BigDecimal winRate = BigDecimal.ZERO;
        if (!closedTrades.isEmpty()) {
            winRate = BigDecimal.valueOf(winningTrades)
                    .divide(BigDecimal.valueOf(closedTrades.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        BigDecimal totalWin = pnlList.stream().filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLoss = pnlList.stream().filter(p -> p.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitFactor = totalLoss.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO :
                totalWin.divide(totalLoss, 2, RoundingMode.HALF_UP);
        BigDecimal avgWin = winningTrades > 0 ? totalWin.divide(BigDecimal.valueOf(winningTrades), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgLoss = losingTrades > 0 ? totalLoss.divide(BigDecimal.valueOf(losingTrades), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal largestWin = pnlList.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal largestLoss = pnlList.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal netPnl = pnlList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectancy = netPnl.divide(BigDecimal.valueOf(closedTrades.size()), 4, RoundingMode.HALF_UP);
        BigDecimal grossPnl = closedTrades.stream()
                .map(ClosedTrade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFee = closedTrades.stream()
                .map(ClosedTrade::getFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeImpactPercent = BigDecimal.ZERO;
        if (grossPnl.abs().compareTo(BigDecimal.ZERO) > 0) {
            feeImpactPercent = totalFee.divide(grossPnl.abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        return new BacktestResult(
                totalReturn, annualizedReturn, maxDrawdown, maxProfit, sharpeRatio,
                closedTrades.size(), winningTrades, losingTrades, winRate, profitFactor,
                avgWin, avgLoss, largestWin, largestLoss,
                expectancy, totalFee, feeImpactPercent, equityCurve
        );
    }

    private BigDecimal calculateMaxDrawdown() {
        BigDecimal peak = equityCurve.get(0);
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = peak.subtract(equity)
                    .divide(peak, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (drawdown.compareTo(maxDd) > 0) {
                maxDd = drawdown;
            }
        }
        return maxDd;
    }

    /**
     * 计算最大盈利（账户峰值相对初始资金的涨幅）
     */
    private BigDecimal calculateMaxProfit() {
        BigDecimal initialCapital = config.getInitialCapital();
        BigDecimal maxEquity = equityCurve.stream()
                .max(BigDecimal::compareTo)
                .orElse(initialCapital);
        return maxEquity.subtract(initialCapital)
                .divide(initialCapital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateSharpeRatio() {
        if (equityCurve.size() < 2) {
            return BigDecimal.ZERO;
        }
        // 计算收益率序列
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal ret = equityCurve.get(i).subtract(equityCurve.get(i - 1))
                    .divide(equityCurve.get(i - 1), 8, RoundingMode.HALF_UP);
            returns.add(ret);
        }
        // 计算平均收益率
        BigDecimal avgReturn = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 8, RoundingMode.HALF_UP);
        // 计算标准差
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal ret : returns) {
            BigDecimal diff = ret.subtract(avgReturn);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(returns.size()), 8, RoundingMode.HALF_UP);
        BigDecimal stdDev = sqrt(variance);
        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        // 年化夏普比率
        return avgReturn.divide(stdDev, 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(Math.sqrt(252)));
    }

    private BigDecimal sqrt(BigDecimal value) {
        // 简化的平方根计算
        return BigDecimal.valueOf(Math.sqrt(value.doubleValue()));
    }
}
