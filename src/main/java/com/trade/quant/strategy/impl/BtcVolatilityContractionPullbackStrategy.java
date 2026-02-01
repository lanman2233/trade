package com.trade.quant.strategy.impl;

import com.trade.quant.backtest.BacktestTradeListener;
import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.Interval;
import com.trade.quant.core.KLine;
import com.trade.quant.core.KLineAggregator;
import com.trade.quant.core.Position;
import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;
import com.trade.quant.core.ConfigManager;
import com.trade.quant.indicator.ATR;
import com.trade.quant.indicator.EMA;
import com.trade.quant.indicator.RSI;
import com.trade.quant.indicator.RollingPercentileTracker;
import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.TradeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * BTC 波动收敛 + 回调再启动（Long Only）
 */
public class BtcVolatilityContractionPullbackStrategy extends AbstractStrategy implements BacktestTradeListener {

    private static final Logger tradeLogger = LoggerFactory.getLogger("TRADE_LOGGER");

    private final EMA ema20;
    private final EMA ema200;
    private final RSI rsi14;
    private final ATR atr14;
    private final RollingPercentileTracker atrPctTracker;

    private final BigDecimal baseMargin;
    private final BigDecimal leverage;
    private final BigDecimal maxLoss;
    private final BigDecimal minStopLossPct;
    private final BigDecimal atrStopLossMultiplier;
    private final BigDecimal tp1Pct;
    private final BigDecimal tp1CloseFraction;
    private final BigDecimal runnerTrailActivationPct;
    private final BigDecimal runnerTrailMinPct;
    private final BigDecimal runnerTrailAtrMultiplier;
    private final BigDecimal rsiPullbackThreshold;
    private final BigDecimal rsiEntryMax;
    private final BigDecimal maxEntryAboveEma20;
    private final BigDecimal maxEntryAboveEma200;
    private final BigDecimal atrPctLower;
    private final BigDecimal atrPctUpper;
    private final BigDecimal maxDailyLoss;
    private final int timeStopBars;
    private final int cooldownAfterStopLossBars;
    private final int maxDailyTrades;
    private final int maxConsecutiveLosses;
    private final int ema200SlopeLookbackHours;
    private final BigDecimal ema200SlopeMinPct;
    private final BigDecimal ema200SlopeMinAbs;

    private boolean pullbackArmed;
    private int holdingBars;
    private BigDecimal entryPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal tp1Price;
    private BigDecimal trailingStopPrice;
    private BigDecimal highestPrice;
    private boolean trailingArmed;
    private boolean tp1Taken;
    private BigDecimal initialQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal currentTradeNetPnl;
    private int cooldownRemaining;

    private LocalDate currentDay;
    private BigDecimal dailyPnl;
    private int dailyTrades;
    private int consecutiveLosses;
    private boolean tradingHalted;

    public BtcVolatilityContractionPullbackStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("BTC-VolCon-PB", symbol, interval, config);

        this.ema20 = new EMA(20);
        this.ema200 = new EMA(200);
        this.rsi14 = new RSI(14);
        this.atr14 = new ATR(14);

        int barsPerDay = Math.max(1, 24 * 60 / interval.getMinutes());
        int windowBars = barsPerDay * 180;
        this.atrPctTracker = new RollingPercentileTracker(windowBars);

        ConfigManager cfg = ConfigManager.getInstance();
        this.baseMargin = cfg.getBigDecimalProperty("btc.vc.base.margin", BigDecimal.valueOf(1000));
        this.leverage = cfg.getBigDecimalProperty("btc.vc.leverage", BigDecimal.valueOf(3));
        this.maxLoss = cfg.getBigDecimalProperty("btc.vc.max.loss", BigDecimal.valueOf(20));
        this.minStopLossPct = cfg.getBigDecimalProperty("btc.vc.min.sl.pct", new BigDecimal("0.006"));
        this.atrStopLossMultiplier = cfg.getBigDecimalProperty("btc.vc.atr.sl.multiplier", new BigDecimal("1.2"));
        this.tp1Pct = cfg.getBigDecimalProperty("btc.vc.tp.pct", new BigDecimal("0.01"));
        this.tp1CloseFraction = cfg.getBigDecimalProperty("btc.vc.tp1.close.fraction", new BigDecimal("0.3"));
        this.runnerTrailActivationPct = cfg.getBigDecimalProperty("btc.vc.runner.trail.activate.pct", new BigDecimal("0.008"));
        this.runnerTrailMinPct = cfg.getBigDecimalProperty("btc.vc.runner.trail.min.pct", new BigDecimal("0.005"));
        this.runnerTrailAtrMultiplier = cfg.getBigDecimalProperty("btc.vc.runner.trail.atr.multiplier", BigDecimal.ONE);
        this.rsiPullbackThreshold = cfg.getBigDecimalProperty("btc.vc.rsi.pullback", new BigDecimal("45"));
        this.rsiEntryMax = cfg.getBigDecimalProperty("btc.vc.rsi.entry.max", new BigDecimal("52"));
        this.maxEntryAboveEma20 = cfg.getBigDecimalProperty("btc.vc.entry.ema20.max.pct", new BigDecimal("0.0010"));
        this.maxEntryAboveEma200 = cfg.getBigDecimalProperty("btc.vc.entry.ema200.max.pct", new BigDecimal("0.012"));
        this.atrPctLower = cfg.getBigDecimalProperty("btc.vc.atr.pct.lower", new BigDecimal("0.30"));
        this.atrPctUpper = cfg.getBigDecimalProperty("btc.vc.atr.pct.upper", new BigDecimal("0.70"));
        this.maxDailyLoss = cfg.getBigDecimalProperty("btc.vc.daily.max.loss", BigDecimal.valueOf(60));
        this.timeStopBars = cfg.getIntProperty("btc.vc.time.stop.bars", 12);
        this.cooldownAfterStopLossBars = cfg.getIntProperty("btc.vc.cooldown.bars", 4);
        this.maxDailyTrades = cfg.getIntProperty("btc.vc.daily.max.trades", 8);
        this.maxConsecutiveLosses = cfg.getIntProperty("btc.vc.max.consecutive.losses", 3);
        this.ema200SlopeLookbackHours = cfg.getIntProperty("btc.vc.ema200.slope.hours", 8);
        this.ema200SlopeMinPct = cfg.getBigDecimalProperty("btc.vc.ema200.slope.min.pct", new BigDecimal("0.0002"));
        this.ema200SlopeMinAbs = cfg.getBigDecimalProperty("btc.vc.ema200.slope.min.abs", BigDecimal.valueOf(10));

        resetState();
    }

    @Override
    public String getName() {
        return "BTC 波动收敛回调再启动";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        if (kLines == null || kLines.size() < 50) {
            return null;
        }

        LocalDate barDay = kLines.get(kLines.size() - 1).getCloseTime().atZone(ZoneOffset.UTC).toLocalDate();
        rollDayIfNeeded(barDay);

        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return null;
        }

        if (tradingHalted || isInCooldown()) {
            return null;
        }

        int slopeLookback = Math.max(1, ema200SlopeLookbackHours);
        List<KLine> hourly = KLineAggregator.aggregate(kLines, interval, Interval.ONE_HOUR);
        if (hourly.size() < 200 + slopeLookback) {
            return null;
        }

        List<BigDecimal> hourCloses = hourly.stream().map(KLine::getClose).toList();
        List<BigDecimal> ema200Series = ema200.calculate(hourCloses);
        if (ema200Series.size() < slopeLookback + 1) {
            return null;
        }
        BigDecimal ema200Val = ema200Series.get(ema200Series.size() - 1);
        BigDecimal ema200Prev = ema200Series.get(ema200Series.size() - 1 - slopeLookback);
        BigDecimal minSlopePct = ema200SlopeMinPct.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO
                : ema200SlopeMinPct;
        BigDecimal ema200Min = ema200Prev.multiply(BigDecimal.ONE.add(minSlopePct));
        BigDecimal minSlopeAbs = ema200SlopeMinAbs.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO
                : ema200SlopeMinAbs;
        BigDecimal ema200Delta = ema200Val.subtract(ema200Prev);
        BigDecimal close1h = hourCloses.get(hourCloses.size() - 1);
        if (close1h.compareTo(ema200Val) <= 0 || ema200Val.compareTo(ema200Min) < 0
                || ema200Delta.compareTo(minSlopeAbs) < 0) {
            pullbackArmed = false;
            return null;
        }

        List<BigDecimal> closes = extractCloses(kLines);
        List<BigDecimal> highs = extractHighs(kLines);
        List<BigDecimal> lows = extractLows(kLines);

        BigDecimal ema20Val = ema20.latest(closes);
        BigDecimal rsiVal = rsi14.latest(closes);
        BigDecimal atrVal = atr14.latest(highs, lows, closes);
        BigDecimal close = getLatestPrice(kLines);

        if (close.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal atrPct = atrVal.divide(close, 8, RoundingMode.HALF_UP);
        atrPctTracker.update(atrPct);

        if (atrPctTracker.hasEnoughSamples()) {
            BigDecimal percentile = atrPctTracker.getPercentile();
            if (percentile.compareTo(atrPctLower) < 0 || percentile.compareTo(atrPctUpper) > 0) {
                return null;
            }
        }

        if (close.compareTo(ema20Val) < 0 && rsiVal.compareTo(rsiPullbackThreshold) < 0) {
            pullbackArmed = true;
        }

        if (pullbackArmed && close.compareTo(ema20Val) > 0) {
            if (kLines.size() < 2) {
                return null;
            }
            BigDecimal prevHigh = kLines.get(kLines.size() - 2).getHigh();
            if (kLines.get(kLines.size() - 1).getHigh().compareTo(prevHigh) <= 0) {
                return null;
            }
            if (!passesEntryFilters(close, ema20Val, ema200Val, rsiVal)) {
                return null;
            }

            BigDecimal slPct = atrPct.multiply(atrStopLossMultiplier).max(minStopLossPct);
            BigDecimal stopLoss = close.multiply(BigDecimal.ONE.subtract(slPct));
            BigDecimal tp1Target = close.multiply(BigDecimal.ONE.add(tp1Pct));

            BigDecimal maxNotional = baseMargin.multiply(leverage);
            BigDecimal riskNotional = maxLoss.divide(slPct, 8, RoundingMode.HALF_UP);
            BigDecimal finalNotional = maxNotional.min(riskNotional);
            BigDecimal quantity = finalNotional.divide(close, 6, RoundingMode.DOWN);

            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }

            pullbackArmed = false;
            recordTrade();

            TradeMetrics metrics = new TradeMetrics(atrPct, rsiVal, ema20Val, ema200Val);
            String reason = "pullback_reentry";
            return new Signal(
                    strategyId,
                    symbol,
                    SignalType.ENTRY_LONG,
                    Side.BUY,
                    close,
                    quantity,
                    stopLoss,
                    tp1Target,
                    reason,
                    metrics,
                    null
            );
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine, List<KLine> allKLines) {
        holdingBars++;

        BigDecimal close = currentKLine.getClose();
        BigDecimal high = currentKLine.getHigh();
        BigDecimal low = currentKLine.getLow();

        if (entryPrice == null) {
            entryPrice = position.getEntryPrice();
        }
        if (stopLossPrice == null) {
            stopLossPrice = position.getStopLoss();
        }
        if (tp1Price == null && entryPrice != null) {
            tp1Price = entryPrice.multiply(BigDecimal.ONE.add(tp1Pct));
        }
        if (initialQuantity == null) {
            initialQuantity = position.getQuantity();
            remainingQuantity = position.getQuantity();
            currentTradeNetPnl = BigDecimal.ZERO;
        }

        if (highestPrice == null || high.compareTo(highestPrice) > 0) {
            highestPrice = high;
        }

        if (!tp1Taken && tp1Price != null && high.compareTo(tp1Price) >= 0) {
            BigDecimal tp1Qty = initialQuantity.multiply(tp1CloseFraction).setScale(6, RoundingMode.DOWN);
            if (tp1Qty.compareTo(position.getQuantity()) > 0) {
                tp1Qty = position.getQuantity();
            }
            if (tp1Qty.compareTo(BigDecimal.ZERO) > 0) {
                tp1Taken = true;
                return exitSignal(position, tp1Price, tp1Qty, ExitReason.TAKE_PROFIT, "tp1", true);
            }
        }

        List<BigDecimal> closes = extractCloses(allKLines);
        BigDecimal ema20Val = ema20.latest(closes);

        if (tp1Taken) {
            if (close.compareTo(ema20Val) < 0) {
                return exitSignal(position, close, position.getQuantity(), ExitReason.STRATEGY_EXIT, "ema20_exit", false);
            }
            if (holdingBars >= timeStopBars) {
                return exitSignal(position, close, position.getQuantity(), ExitReason.TIME_STOP, "time_stop", false);
            }

            if (entryPrice != null && close.compareTo(entryPrice.multiply(BigDecimal.ONE.add(runnerTrailActivationPct))) >= 0) {
                trailingArmed = true;
            }
            if (trailingArmed && highestPrice != null) {
                List<BigDecimal> highs = extractHighs(allKLines);
                List<BigDecimal> lows = extractLows(allKLines);
                BigDecimal atrVal = atr14.latest(highs, lows, closes);
                if (close.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal atrPct = atrVal.divide(close, 8, RoundingMode.HALF_UP);
                    BigDecimal trailDistance = runnerTrailMinPct.max(atrPct.multiply(runnerTrailAtrMultiplier));
                    BigDecimal newTrail = highestPrice.multiply(BigDecimal.ONE.subtract(trailDistance));
                    if (trailingStopPrice == null || newTrail.compareTo(trailingStopPrice) > 0) {
                        trailingStopPrice = newTrail;
                    }
                }
                if (trailingStopPrice != null && low.compareTo(trailingStopPrice) <= 0) {
                    return exitSignal(position, trailingStopPrice, position.getQuantity(), ExitReason.TRAILING_STOP, "runner_trailing", false);
                }
            }
        } else {
            if (holdingBars >= timeStopBars && close.compareTo(ema20Val) < 0) {
                return exitSignal(position, close, position.getQuantity(), ExitReason.TIME_STOP, "time_stop", false);
            }
        }

        return null;
    }

    @Override
    public void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
        this.holdingBars = 0;
        this.entryPrice = position.getEntryPrice();
        this.stopLossPrice = position.getStopLoss();
        this.tp1Price = entryPrice.multiply(BigDecimal.ONE.add(tp1Pct));
        this.highestPrice = entryPrice;
        this.trailingStopPrice = null;
        this.trailingArmed = false;
        this.tp1Taken = false;
        this.initialQuantity = position.getQuantity();
        this.remainingQuantity = position.getQuantity();
        this.currentTradeNetPnl = BigDecimal.ZERO;
    }

    @Override
    public void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
        LocalDate tradeDay = trade.getExitTime().atZone(ZoneOffset.UTC).toLocalDate();
        rollDayIfNeeded(tradeDay);

        dailyPnl = dailyPnl.add(trade.getNetPnl());
        if (currentTradeNetPnl == null) {
            currentTradeNetPnl = BigDecimal.ZERO;
        }
        currentTradeNetPnl = currentTradeNetPnl.add(trade.getNetPnl());

        if (remainingQuantity != null) {
            remainingQuantity = remainingQuantity.subtract(trade.getQuantity());
            if (remainingQuantity.compareTo(BigDecimal.ZERO) < 0) {
                remainingQuantity = BigDecimal.ZERO;
            }
        }

        boolean positionClosed = remainingQuantity == null || remainingQuantity.compareTo(BigDecimal.ZERO) <= 0;
        if (!positionClosed) {
            if (dailyPnl.compareTo(maxDailyLoss.negate()) <= 0) {
                tradingHalted = true;
            }
            tradeLogger.info("[{}] trade_partial pnl={}, reason={}, dailyPnL={}, remaining={}",
                    strategyId, trade.getNetPnl(), reason, dailyPnl, remainingQuantity);
            return;
        }

        dailyTrades++;

        if (currentTradeNetPnl.compareTo(BigDecimal.ZERO) < 0) {
            consecutiveLosses++;
        } else {
            consecutiveLosses = 0;
        }

        if (reason == ExitReason.STOP_LOSS) {
            cooldownRemaining = cooldownAfterStopLossBars;
        }

        if (dailyPnl.compareTo(maxDailyLoss.negate()) <= 0
                || consecutiveLosses >= maxConsecutiveLosses
                || dailyTrades >= maxDailyTrades) {
            tradingHalted = true;
        }

        resetPositionState();

        tradeLogger.info("[{}] trade_closed pnl={}, reason={}, dailyPnL={}, trades={}, lossStreak={}",
                strategyId, currentTradeNetPnl, reason, dailyPnl, dailyTrades, consecutiveLosses);
    }

    @Override
    public void reset() {
        super.reset();
        resetState();
    }

    private Signal exitSignal(Position position, BigDecimal price, ExitReason reason, String msg) {
        return exitSignal(position, price, position.getQuantity(), reason, msg, false);
    }

    private Signal exitSignal(Position position, BigDecimal price, BigDecimal quantity, ExitReason reason, String msg, boolean maker) {
        return new Signal(
                strategyId,
                symbol,
                position.getSide() == PositionSide.LONG ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT,
                position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY,
                price,
                quantity,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                msg,
                null,
                reason,
                maker
        );
    }

    private void rollDayIfNeeded(LocalDate day) {
        if (currentDay == null || !currentDay.equals(day)) {
            currentDay = day;
            dailyPnl = BigDecimal.ZERO;
            dailyTrades = 0;
            consecutiveLosses = 0;
            tradingHalted = false;
        }
    }

    private void resetState() {
        pullbackArmed = false;
        cooldownRemaining = 0;
        currentDay = null;
        dailyPnl = BigDecimal.ZERO;
        dailyTrades = 0;
        consecutiveLosses = 0;
        tradingHalted = false;
        resetPositionState();
        atrPctTracker.reset();
    }

    private void resetPositionState() {
        holdingBars = 0;
        entryPrice = null;
        stopLossPrice = null;
        tp1Price = null;
        trailingStopPrice = null;
        highestPrice = null;
        trailingArmed = false;
        tp1Taken = false;
        initialQuantity = null;
        remainingQuantity = null;
        currentTradeNetPnl = null;
    }

    private boolean passesEntryFilters(BigDecimal entryPrice,
                                       BigDecimal ema20Val,
                                       BigDecimal ema200Val,
                                       BigDecimal rsiVal) {
        if (rsiVal.compareTo(rsiEntryMax) > 0) {
            return false;
        }
        BigDecimal ema20Cap = ema20Val.multiply(BigDecimal.ONE.add(maxEntryAboveEma20));
        if (entryPrice.compareTo(ema20Cap) > 0) {
            return false;
        }
        BigDecimal ema200Cap = ema200Val.multiply(BigDecimal.ONE.add(maxEntryAboveEma200));
        return entryPrice.compareTo(ema200Cap) <= 0;
    }
}
