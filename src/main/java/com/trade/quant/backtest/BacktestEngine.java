package com.trade.quant.backtest;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.Strategy;
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

    private BigDecimal balance;
    private final List<ClosedTrade> closedTrades;  // 已平仓交易记录（包含完整盈亏信息）
    private final List<Position> positions;
    private final List<BigDecimal> equityCurve;

    public BacktestEngine(BacktestConfig config, Exchange exchange, Strategy strategy) {
        this.config = config;
        this.exchange = exchange;
        this.strategy = strategy;
        this.balance = config.getInitialCapital();
        this.closedTrades = new ArrayList<>();
        this.positions = new ArrayList<>();
        this.equityCurve = new ArrayList<>();
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

            if (pos.isStopLossTriggered(kLine.getLow())) {
                // 平仓
                BigDecimal exitPrice = pos.getStopLoss();
                closePosition(pos, exitPrice, kLine.getCloseTime());
                it.remove();
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
        BigDecimal entryPrice = applySlippage(kLine.getClose(), signal.getSide());

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
                BigDecimal exitPrice = applySlippage(kLine.getClose(), signal.getSide());
                closePosition(pos, exitPrice, kLine.getCloseTime());
                it.remove();
                break;
            }
        }
    }

    /**
     * 平仓
     */
    private void closePosition(Position pos, BigDecimal price, Instant time) {
        BigDecimal pnl = calculatePnL(pos, price);
        BigDecimal fee = calculateFee(price, pos.getQuantity());

        // 创建已平仓交易记录
        ClosedTrade closedTrade = new ClosedTrade(
                UUID.randomUUID().toString(),
                pos.getSymbol(),
                pos.getSide(),
                pos.getEntryPrice(),
                price,
                pos.getQuantity(),
                pnl,
                fee,
                pos.getOpenTime(),
                time,
                strategy.getStrategyId()
        );

        closedTrades.add(closedTrade);
        balance = balance.add(pnl).subtract(fee);

        logger.debug("平仓: {} 价格:{} 盈亏:{} 手续费:{}", pos.getSymbol(), price, pnl, fee);
    }

    /**
     * 平掉所有持仓
     */
    private void closeAllPositions(BigDecimal price) {
        for (Position pos : positions) {
            closePosition(pos, price, Instant.now());
        }
        positions.clear();
    }

    /**
     * 计算盈亏
     */
    private BigDecimal calculatePnL(Position pos, BigDecimal exitPrice) {
        BigDecimal priceDiff;
        if (pos.getSide() == PositionSide.LONG) {
            priceDiff = exitPrice.subtract(pos.getEntryPrice());
        } else {
            priceDiff = pos.getEntryPrice().subtract(exitPrice);
        }
        return priceDiff.multiply(pos.getQuantity());
    }

    /**
     * 计算手续费
     */
    private BigDecimal calculateFee(BigDecimal price, BigDecimal quantity) {
        BigDecimal value = price.multiply(quantity);
        return value.multiply(config.getTakerFee());
    }

    /**
     * 应用滑点
     */
    private BigDecimal applySlippage(BigDecimal price, Side side) {
        BigDecimal slippage = price.multiply(config.getSlippage());

        if (side == Side.BUY) {
            return price.add(slippage);
        } else {
            return price.subtract(slippage);
        }
    }

    /**
     * 计算仓位数量
     */
    private BigDecimal calculateQuantity(BigDecimal signalQuantity, BigDecimal price) {
        // 简化处理：使用信号数量或账户余额计算
        BigDecimal maxAffordable = balance.multiply(BigDecimal.valueOf(0.95)).divide(price, 3, RoundingMode.DOWN);
        return signalQuantity.min(maxAffordable);
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

        // 计算年化收益率
        long days = ChronoUnit.DAYS.between(config.getStartTime(), config.getEndTime());
        BigDecimal annualizedReturn = days > 0 ? totalReturn.divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(365)) : BigDecimal.ZERO;

        // 计算夏普比率（简化版，无风险利率设为0）
        BigDecimal sharpeRatio = calculateSharpeRatio();

        // 使用 closedTrades 进行统计（包含真实的盈亏数据）
        if (closedTrades.isEmpty()) {
            return new BacktestResult(
                    totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
                    0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, equityCurve
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

        return new BacktestResult(
                totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
                closedTrades.size(), winningTrades, losingTrades, winRate, profitFactor,
                avgWin, avgLoss, largestWin, largestLoss, equityCurve
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
