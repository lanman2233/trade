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

    private final BacktestConfig config;
    private final Exchange exchange;
    private final Strategy strategy;
    private final BacktestTradeLogger tradeLogger;
    private final Map<Position, TradeMetrics> tradeMetrics;
    private final Map<Position, BigDecimal> entryFees;
    private final List<KLine> preloadedKLines;

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
        this.equityCurve.add(balance);
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

            // 检查持仓止损
            checkStopLoss(kLine);

            // 调用策略的持仓更新方法（让策略有机会生成出场信号）
            // 传入完整历史K线数据，支持策略实时计算指标
            checkPositionUpdates(kLine, availableKLines);

            // 调用策略生成信号
            Signal signal = strategy.analyze(availableKLines);

            if (signal != null) {
                processSignal(signal, kLine);
            }

            // 更新权益
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

            BigDecimal triggerPrice = pos.getSide() == PositionSide.LONG ? kLine.getLow() : kLine.getHigh();
            if (pos.isStopLossTriggered(triggerPrice)) {
                // 平仓
                BigDecimal exitPrice = applySlippage(pos.getStopLoss(), getExitSide(pos), true);
                closePosition(pos, exitPrice, pos.getQuantity(), kLine.getCloseTime(), ExitReason.STOP_LOSS, false);
            }
        }
    }

    /**
     * 检查策略的持仓更新（支持动态出场信号）
     * @param kLine 当前K线
     * @param allKLines 完整的K线历史数据
     */
    private void checkPositionUpdates(KLine kLine, List<KLine> allKLines) {
        Iterator<Position> it = positions.iterator();
        while (it.hasNext()) {
            Position pos = it.next();
            // 传入完整历史K线数据，支持策略实时计算指标
            Signal exitSignal = strategy.onPositionUpdate(pos, kLine, allKLines);
            if (exitSignal != null && exitSignal.isExit()) {
                // 策略要求出场
                boolean closed = handleExitSignal(pos, exitSignal, kLine);
                if (closed) {
                    it.remove();  // 瀹夊叏鍒犻櫎褰撳墠鎸佷粨
                }
                break; // 一次只处理一个出场信号
            }
        }
    }

    /**
     * 处理信号
     */
    private void processSignal(Signal signal, KLine kLine) {
        if (signal.isEntry()) {
            // 入场
            enterPosition(signal, kLine);
        } else if (signal.isExit()) {
            // 出场
            exitPosition(signal, kLine);
        }
    }

    /**
     * 入场
     */
    private void enterPosition(Signal signal, KLine kLine) {
        // 检查是否已有持仓
        for (Position pos : positions) {
            if (pos.getSymbol().equals(signal.getSymbol()) && !pos.isClosed()) {
                return; // 已有持仓，不入场
            }
        }

        // 计算入场价格（包含滑点）
        BigDecimal basePrice = signal.getPrice() != null && signal.getPrice().compareTo(BigDecimal.ZERO) > 0
                ? signal.getPrice()
                : kLine.getClose();
        BigDecimal entryPrice = applySlippage(basePrice, signal.getSide(), true);

        // 计算仓位大小
        BigDecimal quantity = calculateQuantity(signal.getQuantity(), entryPrice);

        // 创建持仓
        PositionSide side = signal.getSide() == Side.BUY ? PositionSide.LONG : PositionSide.SHORT;
        Position position = new Position(
                signal.getSymbol(),
                side,
                entryPrice,
                quantity,
                signal.getStopLoss(),
                config.getLeverage()
        );

        positions.add(position);
        BigDecimal entryFee = calculateFee(entryPrice, quantity, false);
        balance = balance.subtract(entryFee);
        entryFees.put(position, entryFee);

        TradeMetrics metrics = signal.getMetrics();
        if (metrics != null) {
            tradeMetrics.put(position, metrics);
        }

        if (strategy instanceof BacktestTradeListener listener) {
            listener.onPositionOpened(position, signal, kLine, metrics);
        }

        logger.debug("入场: {} {} {} @ {}", signal.getSymbol(), side, quantity, entryPrice);
    }

    /**
     * 出场
     */
    private void exitPosition(Signal signal, KLine kLine) {
        Iterator<Position> it = positions.iterator();
        while (it.hasNext()) {
            Position pos = it.next();

            if (pos.getSymbol().equals(signal.getSymbol()) && !pos.isClosed()) {
                boolean closed = handleExitSignal(pos, signal, kLine);
                if (closed) {
                    it.remove();
                }
                break;
            }
        }
    }

    /**
     * 平仓
     */
    private boolean handleExitSignal(Position pos, Signal signal, KLine kLine) {
        BigDecimal basePrice = signal.getPrice() != null && signal.getPrice().compareTo(BigDecimal.ZERO) > 0
                ? signal.getPrice()
                : kLine.getClose();
        boolean maker = signal.isMaker();
        BigDecimal exitPrice = applySlippage(basePrice, signal.getSide(), !maker);
        ExitReason reason = signal.getExitReason() != null ? signal.getExitReason() : ExitReason.STRATEGY_EXIT;
        BigDecimal requestedQty = signal.getQuantity();
        BigDecimal closeQty = requestedQty == null || requestedQty.compareTo(BigDecimal.ZERO) <= 0
                ? pos.getQuantity()
                : requestedQty.min(pos.getQuantity());

        closePosition(pos, exitPrice, closeQty, kLine.getCloseTime(), reason, maker);
        return pos.isClosed();
    }

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
        }

        logger.debug("平仓: {} 价格:{} 盈亏:{} 手续费:{}", pos.getSymbol(), price, pnl, totalFee);
    }

    /**
     * 平掉所有持仓
     */
    private void closeAllPositions(BigDecimal price) {
        for (Position pos : positions) {
            BigDecimal exitPrice = applySlippage(price, getExitSide(pos), true);
            closePosition(pos, exitPrice, pos.getQuantity(), Instant.now(), ExitReason.FORCE_CLOSE, false);
        }
        positions.clear();
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
    private BigDecimal applySlippage(BigDecimal price, Side side, boolean applySlippage) {
        if (!applySlippage) {
            return price;
        }
        BigDecimal slippage = price.multiply(config.getSlippage());

        if (side == Side.BUY) {
            return price.add(slippage);
        } else {
            return price.subtract(slippage);
        }
    }

    private Side getExitSide(Position position) {
        return position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY;
    }

    /**
     * 计算仓位数量
     */
    private BigDecimal calculateQuantity(BigDecimal signalQuantity, BigDecimal price) {
        // 简化处理：使用信号数量或账户余额计算
        BigDecimal maxAffordable = balance.multiply(BigDecimal.valueOf(0.95)).divide(price, 3, RoundingMode.DOWN);
        return signalQuantity.min(maxAffordable);
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
                    totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
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
                totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
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
