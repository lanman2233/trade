package com.trade.quant.strategy.impl;

import com.trade.quant.backtest.BacktestTradeListener;
import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.ConfigManager;
import com.trade.quant.core.Interval;
import com.trade.quant.core.KLine;
import com.trade.quant.core.Position;
import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;
import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.TradeMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Squeeze breakout strategy (ATR% quantile + range breakout).
 * - Squeeze: atrPct <= quantile over last M days
 * - Breakout: close breaks rangeHigh/Low of last L bars (exclude current)
 * - Trend filter: higher timeframe EMA (1h/4h)
 * - Exit: initial stop + chandelier trailing, optional time stop
 */
public class BtcMa200Rsi6TrendStrategy extends AbstractStrategy implements BacktestTradeListener {

    private final int atrPeriod;
    private final BigDecimal initialStopAtr;
    private final BigDecimal trailAtr;
    private final BigDecimal trailStartAtr;
    private final int rangeLookback;
    private final BigDecimal rangeBreakBuffer;
    private final BigDecimal rangeMinPct;
    private final int retestMaxBars;
    private final boolean retestEnabled;
    private final int atrPctLookbackBars;
    private final BigDecimal atrPctQuantile;
    private final int barsPerDay;
    private final int squeezeMinBars;
    private final BigDecimal squeezeExpandMultiplier;
    private final int squeezeMaxBars;
    private final boolean longOnly;
    private final boolean usePriceConfirm;
    private final int timeStopBars;

    private final int trendFilterHours;
    private final int trendEmaPeriod;
    private final BigDecimal trendEmaMultiplier;
    private final int trendEmaSlopeBars;
    private final boolean exitOnRangeBreak;
    private BigDecimal trendEma;
    private BigDecimal trendEmaInitSum;
    private int trendEmaInitCount;
    private BigDecimal lastTrendClose;
    private boolean trendEmaReady;
    private final Deque<BigDecimal> trendEmaHistory;

    private int processedBars;
    private BigDecimal prevClose;
    private int atrCount;
    private BigDecimal atrSum;
    private BigDecimal currentAtr;

    private final Deque<BigDecimal> atrPctWindow;
    private BigDecimal atrPctThreshold;
    private int lastQuantileUpdateBar;
    private int squeezeCount;
    private boolean squeezeArmed;
    private BigDecimal squeezeRangeHigh;
    private BigDecimal squeezeRangeLow;
    private int squeezeArmedBar;

    private final Map<Position, BigDecimal> maxSinceEntry;
    private final Map<Position, BigDecimal> minSinceEntry;
    private final Map<Position, BigDecimal> entryRangeHigh;
    private final Map<Position, BigDecimal> entryRangeLow;
    private final Map<Position, Integer> entryBarIndex;
    private BigDecimal pendingRangeHigh;
    private BigDecimal pendingRangeLow;
    private Side pendingSide;
    private Side retestSide;
    private BigDecimal retestLevel;
    private BigDecimal retestRangeHigh;
    private BigDecimal retestRangeLow;
    private int retestArmedBar;

    public BtcMa200Rsi6TrendStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("BTC-SQUEEZE-DONCHIAN", symbol, interval, config);
        ConfigManager cfg = ConfigManager.getInstance();

        this.atrPeriod = cfg.getIntProperty("btc.simple.atr.period", 14);
        this.initialStopAtr = cfg.getBigDecimalProperty("btc.simple.initial.stop.atr", new BigDecimal("1.5"));
        this.trailAtr = cfg.getBigDecimalProperty("btc.simple.trail.atr.multiplier", new BigDecimal("2.5"));
        this.trailStartAtr = cfg.getBigDecimalProperty("btc.simple.trail.start.atr", BigDecimal.ZERO);
        this.rangeLookback = cfg.getIntProperty("btc.simple.range.lookback", 24);
        this.rangeBreakBuffer = cfg.getBigDecimalProperty("btc.simple.range.break.buffer", BigDecimal.ZERO);
        this.rangeMinPct = cfg.getBigDecimalProperty("btc.simple.range.min.pct", BigDecimal.ZERO);
        this.retestMaxBars = cfg.getIntProperty("btc.simple.retest.max.bars", 0);
        this.retestEnabled = cfg.getBooleanProperty("btc.simple.retest.enabled", true);
        int lookbackDays = cfg.getIntProperty("btc.simple.atrpct.lookback.days", 180);
        this.atrPctQuantile = cfg.getBigDecimalProperty("btc.simple.atrpct.quantile", new BigDecimal("0.20"));
        this.longOnly = cfg.getBooleanProperty("btc.simple.long.only", true);
        this.usePriceConfirm = cfg.getBooleanProperty("btc.simple.entry.price.confirm", true);
        this.timeStopBars = cfg.getIntProperty("btc.simple.time.stop.bars", 0);
        this.squeezeMinBars = cfg.getIntProperty("btc.simple.squeeze.min.bars", 12);
        this.squeezeExpandMultiplier = cfg.getBigDecimalProperty("btc.simple.squeeze.expand.mult", new BigDecimal("1.2"));
        this.squeezeMaxBars = cfg.getIntProperty("btc.simple.squeeze.max.bars", 48);
        this.exitOnRangeBreak = cfg.getBooleanProperty("btc.simple.exit.on.range", true);

        this.barsPerDay = Math.max(1, (24 * 60) / interval.getMinutes());
        this.atrPctLookbackBars = Math.max(1, lookbackDays * barsPerDay);

        this.trendFilterHours = cfg.getIntProperty("btc.simple.trend.filter.hours", 1);
        this.trendEmaPeriod = cfg.getIntProperty("btc.simple.trend.ema.period", 200);
        this.trendEmaSlopeBars = cfg.getIntProperty("btc.simple.trend.ema.slope.bars", 24);
        this.trendEmaMultiplier = trendEmaPeriod > 0
                ? BigDecimal.valueOf(2).divide(BigDecimal.valueOf(trendEmaPeriod + 1L), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        this.atrPctWindow = new ArrayDeque<>();
        this.maxSinceEntry = new HashMap<>();
        this.minSinceEntry = new HashMap<>();
        this.entryRangeHigh = new HashMap<>();
        this.entryRangeLow = new HashMap<>();
        this.entryBarIndex = new HashMap<>();
        this.trendEmaHistory = new ArrayDeque<>();
        resetIndicatorState();
    }

    @Override
    public String getName() {
        return "BTC Squeeze Breakout (ATR% + Donchian)";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        if (kLines == null || kLines.size() < Math.max(rangeLookback + 1, atrPeriod + 1)) {
            return null;
        }

        updateIndicators(kLines);
        if (currentAtr == null || atrPctThreshold == null) {
            return null;
        }

        int barIndex = kLines.size() - 1;
        KLine latest = kLines.get(barIndex);
        BigDecimal close = latest.getClose();
        BigDecimal open = latest.getOpen();
        BigDecimal high = latest.getHigh();
        BigDecimal low = latest.getLow();
        if (close.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal atrPct = currentAtr.divide(close, 8, RoundingMode.HALF_UP);
        boolean inSqueeze = atrPct.compareTo(atrPctThreshold) <= 0;
        if (inSqueeze) {
            squeezeCount++;
            if (!squeezeArmed && (squeezeMinBars <= 0 || squeezeCount >= squeezeMinBars)) {
                squeezeArmed = true;
                squeezeArmedBar = barIndex;
            }
            if (squeezeArmed) {
                BigDecimal rangeHigh = highestHigh(kLines, barIndex, rangeLookback);
                BigDecimal rangeLow = lowestLow(kLines, barIndex, rangeLookback);
                if (rangeHigh != null && rangeLow != null) {
                    squeezeRangeHigh = rangeHigh;
                    squeezeRangeLow = rangeLow;
                }
            }
        } else {
            squeezeCount = 0;
        }

        boolean trendFilterEnabled = trendFilterHours > 0 && trendEmaPeriod > 0;
        if (trendFilterEnabled && (!trendEmaReady || lastTrendClose == null || trendEma == null)) {
            return null;
        }
        boolean trendLongOk = !trendFilterEnabled || lastTrendClose.compareTo(trendEma) > 0;
        boolean trendShortOk = !trendFilterEnabled || lastTrendClose.compareTo(trendEma) < 0;
        if (trendFilterEnabled && trendEmaSlopeBars > 0) {
            BigDecimal emaPast = getTrendEmaBack(trendEmaSlopeBars);
            if (emaPast != null) {
                boolean emaSlopeUp = trendEma.compareTo(emaPast) > 0;
                boolean emaSlopeDown = trendEma.compareTo(emaPast) < 0;
                trendLongOk = trendLongOk && emaSlopeUp;
                trendShortOk = trendShortOk && emaSlopeDown;
            } else {
                return null;
            }
        }

        boolean priceConfirmLong = !usePriceConfirm || close.compareTo(open) > 0;
        boolean priceConfirmShort = !usePriceConfirm || close.compareTo(open) < 0;

        TradeMetrics metrics = new TradeMetrics(atrPct, null, null, null);
        BigDecimal quantity = BigDecimal.ZERO;

        if (!retestEnabled && retestSide != null) {
            clearRetest();
        }

        if (retestEnabled && retestSide != null) {
            if (retestMaxBars > 0 && retestArmedBar >= 0 && barIndex - retestArmedBar > retestMaxBars) {
                clearRetest();
            } else if (barIndex > retestArmedBar && retestLevel != null) {
                if (retestSide == Side.BUY) {
                    if (trendLongOk && priceConfirmLong
                            && low.compareTo(retestLevel) <= 0
                            && close.compareTo(retestLevel) > 0) {
                        pendingRangeHigh = retestRangeHigh;
                        pendingRangeLow = retestRangeLow;
                        pendingSide = Side.BUY;
                        BigDecimal stopLoss = close.subtract(currentAtr.multiply(initialStopAtr));
                        clearRetest();
                        return new Signal(
                                strategyId,
                                symbol,
                                SignalType.ENTRY_LONG,
                                Side.BUY,
                                close,
                                quantity,
                                stopLoss,
                                BigDecimal.ZERO,
                                "squeeze_retest_long",
                                metrics,
                                null,
                                false
                        );
                    }
                } else if (!longOnly) {
                    if (trendShortOk && priceConfirmShort
                            && high.compareTo(retestLevel) >= 0
                            && close.compareTo(retestLevel) < 0) {
                        pendingRangeHigh = retestRangeHigh;
                        pendingRangeLow = retestRangeLow;
                        pendingSide = Side.SELL;
                        BigDecimal stopLoss = close.add(currentAtr.multiply(initialStopAtr));
                        clearRetest();
                        return new Signal(
                                strategyId,
                                symbol,
                                SignalType.ENTRY_SHORT,
                                Side.SELL,
                                close,
                                quantity,
                                stopLoss,
                                BigDecimal.ZERO,
                                "squeeze_retest_short",
                                metrics,
                                null,
                                false
                        );
                    }
                }
            }
            return null;
        }

        if (!squeezeArmed) {
            return null;
        }
        if (squeezeMaxBars > 0 && squeezeArmedBar >= 0 && barIndex - squeezeArmedBar > squeezeMaxBars) {
            squeezeArmed = false;
            return null;
        }

        BigDecimal expandThreshold = atrPctThreshold.multiply(squeezeExpandMultiplier);
        if (atrPct.compareTo(expandThreshold) < 0) {
            return null; // no expansion confirmation
        }

        if (barIndex < rangeLookback) {
            return null;
        }

        BigDecimal rangeHigh = squeezeRangeHigh;
        BigDecimal rangeLow = squeezeRangeLow;
        if (rangeHigh == null || rangeLow == null) {
            return null;
        }
        BigDecimal buffer = rangeBreakBuffer != null ? rangeBreakBuffer : BigDecimal.ZERO;
        if (buffer.compareTo(BigDecimal.ZERO) < 0) {
            buffer = BigDecimal.ZERO;
        }
        BigDecimal longThreshold = rangeHigh.multiply(BigDecimal.ONE.add(buffer));
        BigDecimal shortThreshold = rangeLow.multiply(BigDecimal.ONE.subtract(buffer));

        BigDecimal minPct = rangeMinPct != null ? rangeMinPct : BigDecimal.ZERO;
        if (minPct.compareTo(BigDecimal.ZERO) < 0) {
            minPct = BigDecimal.ZERO;
        }
        BigDecimal rangeWidthPct = rangeHigh.subtract(rangeLow)
                .abs()
                .divide(close, 8, RoundingMode.HALF_UP);
        if (minPct.compareTo(BigDecimal.ZERO) > 0 && rangeWidthPct.compareTo(minPct) < 0) {
            return null;
        }

        if (trendLongOk && priceConfirmLong && close.compareTo(longThreshold) > 0) {
            if (retestEnabled) {
                retestSide = Side.BUY;
                retestLevel = longThreshold;
                retestRangeHigh = rangeHigh;
                retestRangeLow = rangeLow;
                retestArmedBar = barIndex;
                squeezeArmed = false;
                return null;
            }
            pendingRangeHigh = rangeHigh;
            pendingRangeLow = rangeLow;
            pendingSide = Side.BUY;
            BigDecimal stopLoss = close.subtract(currentAtr.multiply(initialStopAtr));
            squeezeArmed = false;
            return new Signal(
                    strategyId,
                    symbol,
                    SignalType.ENTRY_LONG,
                    Side.BUY,
                    close,
                    quantity,
                    stopLoss,
                    BigDecimal.ZERO,
                    "squeeze_breakout_long",
                    metrics,
                    null,
                    false
            );
        }

        if (!longOnly && trendShortOk && priceConfirmShort && close.compareTo(shortThreshold) < 0) {
            if (retestEnabled) {
                retestSide = Side.SELL;
                retestLevel = shortThreshold;
                retestRangeHigh = rangeHigh;
                retestRangeLow = rangeLow;
                retestArmedBar = barIndex;
                squeezeArmed = false;
                return null;
            }
            pendingRangeHigh = rangeHigh;
            pendingRangeLow = rangeLow;
            pendingSide = Side.SELL;
            BigDecimal stopLoss = close.add(currentAtr.multiply(initialStopAtr));
            squeezeArmed = false;
            return new Signal(
                    strategyId,
                    symbol,
                    SignalType.ENTRY_SHORT,
                    Side.SELL,
                    close,
                    quantity,
                    stopLoss,
                    BigDecimal.ZERO,
                    "squeeze_breakout_short",
                    metrics,
                    null,
                    false
            );
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine, List<KLine> allKLines) {
        updateIndicators(allKLines);
        if (currentAtr == null) {
            return null;
        }

        Integer entryIndex = entryBarIndex.get(position);
        int currentIndex = allKLines.size() - 1;
        if (timeStopBars > 0 && entryIndex != null && currentIndex - entryIndex >= timeStopBars) {
            return exitSignal(position, currentKLine.getClose(), ExitReason.TIME_STOP, "time_stop", false);
        }

        BigDecimal high = currentKLine.getHigh();
        BigDecimal low = currentKLine.getLow();
        BigDecimal entry = position.getEntryPrice();

        if (position.getSide() == PositionSide.LONG) {
            BigDecimal maxHigh = maxSinceEntry.getOrDefault(position, entry);
            if (high.compareTo(maxHigh) > 0) {
                maxHigh = high;
                maxSinceEntry.put(position, maxHigh);
            }
            BigDecimal initialStop = entry.subtract(currentAtr.multiply(initialStopAtr));
            BigDecimal effectiveStop = initialStop;
            BigDecimal move = maxHigh.subtract(entry);
            boolean trailActive = trailStartAtr == null
                    || trailStartAtr.compareTo(BigDecimal.ZERO) <= 0
                    || move.compareTo(currentAtr.multiply(trailStartAtr)) >= 0;
            if (trailActive) {
                BigDecimal trailStop = maxHigh.subtract(currentAtr.multiply(trailAtr));
                effectiveStop = trailStop.max(initialStop);
            }
            if (exitOnRangeBreak) {
                BigDecimal rangeLow = entryRangeLow.get(position);
                if (rangeLow != null && currentKLine.getClose().compareTo(rangeLow) < 0) {
                    return exitSignal(position, currentKLine.getClose(), ExitReason.STRATEGY_EXIT, "range_break_down", false);
                }
            }
            if (low.compareTo(effectiveStop) <= 0) {
                return exitSignal(position, effectiveStop, ExitReason.STOP_LOSS, "trail_stop", false);
            }
        } else {
            BigDecimal minLow = minSinceEntry.getOrDefault(position, entry);
            if (low.compareTo(minLow) < 0) {
                minLow = low;
                minSinceEntry.put(position, minLow);
            }
            BigDecimal initialStop = entry.add(currentAtr.multiply(initialStopAtr));
            BigDecimal effectiveStop = initialStop;
            BigDecimal move = entry.subtract(minLow);
            boolean trailActive = trailStartAtr == null
                    || trailStartAtr.compareTo(BigDecimal.ZERO) <= 0
                    || move.compareTo(currentAtr.multiply(trailStartAtr)) >= 0;
            if (trailActive) {
                BigDecimal trailStop = minLow.add(currentAtr.multiply(trailAtr));
                effectiveStop = trailStop.min(initialStop);
            }
            if (exitOnRangeBreak) {
                BigDecimal rangeHigh = entryRangeHigh.get(position);
                if (rangeHigh != null && currentKLine.getClose().compareTo(rangeHigh) > 0) {
                    return exitSignal(position, currentKLine.getClose(), ExitReason.STRATEGY_EXIT, "range_break_up", false);
                }
            }
            if (high.compareTo(effectiveStop) >= 0) {
                return exitSignal(position, effectiveStop, ExitReason.STOP_LOSS, "trail_stop", false);
            }
        }

        return null;
    }

    @Override
    public void reset() {
        super.reset();
        resetIndicatorState();
    }

    private void resetIndicatorState() {
        processedBars = 0;
        prevClose = null;
        atrCount = 0;
        atrSum = BigDecimal.ZERO;
        currentAtr = null;
        atrPctWindow.clear();
        atrPctThreshold = null;
        lastQuantileUpdateBar = -1;
        squeezeCount = 0;
        squeezeArmed = false;
        squeezeRangeHigh = null;
        squeezeRangeLow = null;
        squeezeArmedBar = -1;

        trendEma = null;
        trendEmaInitSum = BigDecimal.ZERO;
        trendEmaInitCount = 0;
        lastTrendClose = null;
        trendEmaReady = false;
        trendEmaHistory.clear();

        maxSinceEntry.clear();
        minSinceEntry.clear();
        entryRangeHigh.clear();
        entryRangeLow.clear();
        entryBarIndex.clear();
        pendingRangeHigh = null;
        pendingRangeLow = null;
        pendingSide = null;
        clearRetest();
    }

    private void updateIndicators(List<KLine> kLines) {
        for (int i = processedBars; i < kLines.size(); i++) {
            KLine k = kLines.get(i);
            BigDecimal close = k.getClose();

            if (prevClose != null) {
                BigDecimal high = k.getHigh();
                BigDecimal low = k.getLow();
                BigDecimal tr = high.subtract(low);
                BigDecimal highPrev = high.subtract(prevClose).abs();
                if (highPrev.compareTo(tr) > 0) {
                    tr = highPrev;
                }
                BigDecimal lowPrev = low.subtract(prevClose).abs();
                if (lowPrev.compareTo(tr) > 0) {
                    tr = lowPrev;
                }

                if (atrCount < atrPeriod) {
                    atrSum = atrSum.add(tr);
                    atrCount++;
                    if (atrCount == atrPeriod) {
                        currentAtr = atrSum.divide(BigDecimal.valueOf(atrPeriod), 8, RoundingMode.HALF_UP);
                    }
                } else {
                    currentAtr = currentAtr.multiply(BigDecimal.valueOf(atrPeriod - 1L))
                            .add(tr)
                            .divide(BigDecimal.valueOf(atrPeriod), 8, RoundingMode.HALF_UP);
                }

                if (currentAtr != null && close.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal atrPct = currentAtr.divide(close, 8, RoundingMode.HALF_UP);
                    atrPctWindow.addLast(atrPct);
                    if (atrPctWindow.size() > atrPctLookbackBars) {
                        atrPctWindow.removeFirst();
                    }
                }
            }

            if (isTrendBarClose(k.getOpenTime())) {
                updateTrendEma(close);
            }

            prevClose = close;
            processedBars++;
        }

        if (atrPctWindow.size() >= atrPctLookbackBars) {
            boolean shouldUpdate = lastQuantileUpdateBar < 0
                    || processedBars - lastQuantileUpdateBar >= barsPerDay;
            if (shouldUpdate) {
                atrPctThreshold = computeQuantile(new ArrayList<>(atrPctWindow), atrPctQuantile);
                lastQuantileUpdateBar = processedBars;
            }
        }
    }

    private BigDecimal computeQuantile(List<BigDecimal> values, BigDecimal q) {
        if (values.isEmpty()) {
            return null;
        }
        double quant = q != null ? q.doubleValue() : 0.2;
        if (quant < 0) quant = 0;
        if (quant > 1) quant = 1;
        Collections.sort(values);
        int idx = (int) Math.floor((values.size() - 1) * quant);
        return values.get(idx);
    }

    private BigDecimal highestHigh(List<KLine> kLines, int endIndexExclusive, int lookback) {
        int start = endIndexExclusive - lookback;
        if (start < 0) {
            return null;
        }
        BigDecimal max = null;
        for (int i = start; i < endIndexExclusive; i++) {
            BigDecimal high = kLines.get(i).getHigh();
            if (max == null || high.compareTo(max) > 0) {
                max = high;
            }
        }
        return max;
    }

    private BigDecimal lowestLow(List<KLine> kLines, int endIndexExclusive, int lookback) {
        int start = endIndexExclusive - lookback;
        if (start < 0) {
            return null;
        }
        BigDecimal min = null;
        for (int i = start; i < endIndexExclusive; i++) {
            BigDecimal low = kLines.get(i).getLow();
            if (min == null || low.compareTo(min) < 0) {
                min = low;
            }
        }
        return min;
    }

    private boolean isTrendBarClose(Instant openTime) {
        if (openTime == null || trendFilterHours <= 0) {
            return false;
        }
        long minutes = openTime.getEpochSecond() / 60;
        int minuteOfHour = (int) (minutes % 60);
        int hourOfDay = (int) ((minutes / 60) % 24);
        int expectedMinute = 60 - interval.getMinutes();
        if (minuteOfHour != expectedMinute) {
            return false;
        }
        if (trendFilterHours <= 1) {
            return true;
        }
        return hourOfDay % trendFilterHours == (trendFilterHours - 1);
    }

    private void updateTrendEma(BigDecimal close) {
        if (close == null) {
            return;
        }
        lastTrendClose = close;
        if (trendEmaPeriod <= 0) {
            trendEmaReady = true;
            return;
        }
        if (!trendEmaReady) {
            trendEmaInitSum = trendEmaInitSum.add(close);
            trendEmaInitCount++;
            if (trendEmaInitCount >= trendEmaPeriod) {
                trendEma = trendEmaInitSum.divide(BigDecimal.valueOf(trendEmaPeriod), 8, RoundingMode.HALF_UP);
                trendEmaReady = true;
                recordTrendEma(trendEma);
            }
            return;
        }
        trendEma = close.subtract(trendEma)
                .multiply(trendEmaMultiplier)
                .add(trendEma)
                .setScale(8, RoundingMode.HALF_UP);
        recordTrendEma(trendEma);
    }

    private void recordTrendEma(BigDecimal ema) {
        if (ema == null) {
            return;
        }
        trendEmaHistory.addLast(ema);
        if (trendEmaSlopeBars > 0) {
            int maxSize = trendEmaSlopeBars + 1;
            while (trendEmaHistory.size() > maxSize) {
                trendEmaHistory.removeFirst();
            }
        }
    }

    private BigDecimal getTrendEmaBack(int barsBack) {
        if (barsBack <= 0 || trendEmaHistory.isEmpty()) {
            return trendEma;
        }
        int targetIndex = trendEmaHistory.size() - 1 - barsBack;
        if (targetIndex < 0) {
            return null;
        }
        int idx = 0;
        for (BigDecimal value : trendEmaHistory) {
            if (idx == targetIndex) {
                return value;
            }
            idx++;
        }
        return null;
    }

    private void clearRetest() {
        retestSide = null;
        retestLevel = null;
        retestRangeHigh = null;
        retestRangeLow = null;
        retestArmedBar = -1;
    }

    private Signal exitSignal(Position position, BigDecimal price, ExitReason reason, String msg, boolean maker) {
        return new Signal(
                strategyId,
                symbol,
                position.getSide() == PositionSide.LONG ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT,
                position.getSide() == PositionSide.LONG ? Side.SELL : Side.BUY,
                price,
                position.getQuantity(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                msg,
                null,
                reason,
                maker
        );
    }

    @Override
    public void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
        if (position == null) {
            return;
        }
        maxSinceEntry.put(position, position.getEntryPrice());
        minSinceEntry.put(position, position.getEntryPrice());
        entryBarIndex.put(position, kLine != null ? (processedBars - 1) : null);
        if (pendingSide == Side.BUY) {
            entryRangeHigh.put(position, pendingRangeHigh);
            entryRangeLow.put(position, pendingRangeLow);
        } else if (pendingSide == Side.SELL) {
            entryRangeHigh.put(position, pendingRangeHigh);
            entryRangeLow.put(position, pendingRangeLow);
        }
        pendingRangeHigh = null;
        pendingRangeLow = null;
        pendingSide = null;
    }

    @Override
    public void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
        if (trade != null) {
            maxSinceEntry.entrySet().removeIf(e -> e.getKey().getSymbol().equals(trade.getSymbol()));
            minSinceEntry.entrySet().removeIf(e -> e.getKey().getSymbol().equals(trade.getSymbol()));
            entryRangeHigh.entrySet().removeIf(e -> e.getKey().getSymbol().equals(trade.getSymbol()));
            entryRangeLow.entrySet().removeIf(e -> e.getKey().getSymbol().equals(trade.getSymbol()));
            entryBarIndex.entrySet().removeIf(e -> e.getKey().getSymbol().equals(trade.getSymbol()));
        }
    }
}
