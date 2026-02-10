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
import com.trade.quant.strategy.EquityAwareStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.TradeMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BTCUSDT 15m Donchian48 breakout strategy (immediate market fill).
 */
public class BtcDonchian48BreakoutStrategy extends AbstractStrategy implements BacktestTradeListener, EquityAwareStrategy {

    private static final int DONCHIAN_PERIOD = 48;
    private static final int ATR_PERIOD = 48;
    private static final int WARMUP_BARS = DONCHIAN_PERIOD + ATR_PERIOD;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final BigDecimal riskPct;
    private final BigDecimal qtyStep;
    private final BigDecimal minQty;
    private final BigDecimal minNotional;
    private final BigDecimal minAtrPct;
    private final BigDecimal costRate;
    private final BigDecimal initialEquity;
    private final boolean liveMode;

    private BigDecimal equity;

    private final Map<Position, BigDecimal> highestSinceEntry;
    private final Map<Position, BigDecimal> lowestSinceEntry;
    private final Map<Position, BigDecimal> stopLevel;

    public BtcDonchian48BreakoutStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        this(symbol, interval, config, "live".equalsIgnoreCase(
                ConfigManager.getInstance().getProperty("app.mode", "backtest")
        ));
    }

    public BtcDonchian48BreakoutStrategy(Symbol symbol, Interval interval, StrategyConfig config, boolean liveMode) {
        super("BTC-DONCHIAN48-BREAKOUT", symbol, interval, config);
        ConfigManager cfg = ConfigManager.getInstance();
        this.riskPct = cfg.getBigDecimalProperty("risk.per.trade", new BigDecimal("0.01"));
        this.qtyStep = cfg.getBigDecimalProperty("btc.donchian.qty.step", new BigDecimal("0.001"));
        this.minQty = cfg.getBigDecimalProperty("btc.donchian.min.qty", new BigDecimal("0.001"));
        this.minNotional = cfg.getBigDecimalProperty("btc.donchian.min.notional", new BigDecimal("5"));
        this.minAtrPct = cfg.getBigDecimalProperty("btc.donchian.min.atr.pct", new BigDecimal("0.004"));
        this.liveMode = liveMode;
        BigDecimal spread = cfg.getBigDecimalProperty("backtest.spread", BigDecimal.ZERO);
        BigDecimal slippage = cfg.getBigDecimalProperty("backtest.slippage", BigDecimal.ZERO);
        this.costRate = this.liveMode ? BigDecimal.ZERO : spread.add(slippage);
        this.initialEquity = cfg.getBigDecimalProperty("backtest.initial.capital", new BigDecimal("10000"));
        this.equity = initialEquity;
        this.highestSinceEntry = new HashMap<>();
        this.lowestSinceEntry = new HashMap<>();
        this.stopLevel = new HashMap<>();
    }

    @Override
    public String getName() {
        return "BTC Donchian48 Breakout";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        if (kLines == null || kLines.size() < WARMUP_BARS) {
            return null;
        }

        int index = kLines.size() - 1;
        KLine current = kLines.get(index);
        BigDecimal upper = highestHigh(kLines, index, DONCHIAN_PERIOD);
        BigDecimal lower = lowestLow(kLines, index, DONCHIAN_PERIOD);
        BigDecimal atr = calculateAtrSma(kLines, index, ATR_PERIOD);
        if (upper == null || lower == null || atr == null) {
            return null;
        }

        BigDecimal close = current.getClose();
        if (close.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal atrPct = atr.divide(close, 8, RoundingMode.HALF_UP);
        if (atrPct.compareTo(minAtrPct) < 0) {
            return null;
        }

        BigDecimal high = current.getHigh();
        BigDecimal low = current.getLow();
        if (high.compareTo(upper) >= 0) {
            Signal signal = buildEntrySignal(Side.BUY, current, upper, lower, atr);
            if (signal != null) {
                recordTrade();
            }
            return signal;
        }
        if (low.compareTo(lower) <= 0) {
            Signal signal = buildEntrySignal(Side.SELL, current, upper, lower, atr);
            if (signal != null) {
                recordTrade();
            }
            return signal;
        }
        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine, List<KLine> allKLines) {
        if (position == null || currentKLine == null || allKLines == null) {
            return null;
        }
        int index = allKLines.size() - 1;
        BigDecimal atr = calculateAtrSma(allKLines, index, ATR_PERIOD);
        if (atr == null) {
            return null;
        }

        BigDecimal stop = stopLevel.getOrDefault(position, position.getStopLoss());
        if (position.getSide() == PositionSide.LONG) {
            BigDecimal highest = highestSinceEntry.getOrDefault(position, position.getEntryPrice());
            if (currentKLine.getHigh().compareTo(highest) > 0) {
                highest = currentKLine.getHigh();
            }
            highestSinceEntry.put(position, highest);
            BigDecimal trail = highest.subtract(atr.multiply(TWO));
            if (trail.compareTo(stop) > 0) {
                stop = trail;
            }
            stopLevel.put(position, stop);
            position.updateStopLoss(stop);
            if (currentKLine.getLow().compareTo(stop) <= 0) {
                return buildExitSignal(position, stop, Side.SELL);
            }
        } else {
            BigDecimal lowest = lowestSinceEntry.getOrDefault(position, position.getEntryPrice());
            if (currentKLine.getLow().compareTo(lowest) < 0) {
                lowest = currentKLine.getLow();
            }
            lowestSinceEntry.put(position, lowest);
            BigDecimal trail = lowest.add(atr.multiply(TWO));
            if (trail.compareTo(stop) < 0) {
                stop = trail;
            }
            stopLevel.put(position, stop);
            position.updateStopLoss(stop);
            if (currentKLine.getHigh().compareTo(stop) >= 0) {
                return buildExitSignal(position, stop, Side.BUY);
            }
        }
        return null;
    }

    @Override
    public void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
        if (position == null || kLine == null) {
            return;
        }
        highestSinceEntry.put(position, kLine.getHigh());
        lowestSinceEntry.put(position, kLine.getLow());
        stopLevel.put(position, position.getStopLoss());
    }

    @Override
    public void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
        if (trade != null) {
            equity = equity.add(trade.getNetPnl());
        }
        highestSinceEntry.clear();
        lowestSinceEntry.clear();
        stopLevel.clear();
    }

    @Override
    public void reset() {
        super.reset();
        equity = initialEquity;
        highestSinceEntry.clear();
        lowestSinceEntry.clear();
        stopLevel.clear();
    }

    @Override
    public void updateEquity(BigDecimal equity) {
        if (equity != null && equity.compareTo(BigDecimal.ZERO) > 0) {
            this.equity = equity;
        }
    }

    private Signal buildEntrySignal(Side side, KLine current, BigDecimal upper, BigDecimal lower, BigDecimal atr) {
        BigDecimal theoretical = side == Side.BUY
                ? current.getOpen().max(upper)
                : current.getOpen().min(lower);
        BigDecimal entryFill = applyCost(theoretical, side);
        BigDecimal stopDistance = atr.multiply(TWO);
        BigDecimal stopPrice = side == Side.BUY
                ? entryFill.subtract(stopDistance)
                : entryFill.add(stopDistance);
        BigDecimal quantity = liveMode ? BigDecimal.ZERO : calculateQuantity(entryFill, stopPrice);
        if (!liveMode && quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal atrPct = BigDecimal.ZERO;
        if (current.getClose().compareTo(BigDecimal.ZERO) > 0) {
            atrPct = atr.divide(current.getClose(), 8, RoundingMode.HALF_UP);
        }
        TradeMetrics metrics = new TradeMetrics(atrPct, null, null, null);

        SignalType type = side == Side.BUY ? SignalType.ENTRY_LONG : SignalType.ENTRY_SHORT;
        String reason = side == Side.BUY ? "donchian_breakout_long" : "donchian_breakout_short";
        return new Signal(
                strategyId,
                symbol,
                type,
                side,
                entryFill,
                quantity,
                stopPrice,
                BigDecimal.ZERO,
                reason,
                metrics,
                null,
                false,
                0,
                true,
                true,
                false
        );
    }

    private Signal buildExitSignal(Position position, BigDecimal stop, Side exitSide) {
        BigDecimal fill = applyCost(stop, exitSide);
        SignalType type = position.getSide() == PositionSide.LONG ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
        return new Signal(
                strategyId,
                symbol,
                type,
                exitSide,
                fill,
                position.getQuantity(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "trailing_stop",
                null,
                ExitReason.TRAILING_STOP,
                false,
                0,
                true,
                true,
                true
        );
    }

    private BigDecimal calculateQuantity(BigDecimal entryFill, BigDecimal stopPrice) {
        if (entryFill == null || stopPrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskDistance = entryFill.subtract(stopPrice).abs();
        if (riskDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskAmount = equity.multiply(riskPct);
        BigDecimal qtyRaw = riskAmount.divide(riskDistance, 8, RoundingMode.DOWN);
        BigDecimal qty = applyQtyStep(qtyRaw);
        if (qty.compareTo(minQty) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal notional = qty.multiply(entryFill);
        if (notional.compareTo(minNotional) < 0) {
            return BigDecimal.ZERO;
        }
        return qty;
    }

    private BigDecimal applyQtyStep(BigDecimal qtyRaw) {
        if (qtyStep == null || qtyStep.compareTo(BigDecimal.ZERO) <= 0) {
            return qtyRaw;
        }
        BigDecimal steps = qtyRaw.divide(qtyStep, 0, RoundingMode.DOWN);
        return steps.multiply(qtyStep);
    }

    private BigDecimal applyCost(BigDecimal price, Side side) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        if (costRate == null || costRate.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal factor = side == Side.BUY
                ? BigDecimal.ONE.add(costRate)
                : BigDecimal.ONE.subtract(costRate);
        return price.multiply(factor);
    }

    private BigDecimal highestHigh(List<KLine> kLines, int endIndex, int lookback) {
        int start = endIndex - lookback;
        if (start < 0) {
            return null;
        }
        BigDecimal max = null;
        for (int i = start; i <= endIndex - 1; i++) {
            BigDecimal high = kLines.get(i).getHigh();
            if (max == null || high.compareTo(max) > 0) {
                max = high;
            }
        }
        return max;
    }

    private BigDecimal lowestLow(List<KLine> kLines, int endIndex, int lookback) {
        int start = endIndex - lookback;
        if (start < 0) {
            return null;
        }
        BigDecimal min = null;
        for (int i = start; i <= endIndex - 1; i++) {
            BigDecimal low = kLines.get(i).getLow();
            if (min == null || low.compareTo(min) < 0) {
                min = low;
            }
        }
        return min;
    }

    private BigDecimal calculateAtrSma(List<KLine> kLines, int endIndex, int period) {
        int start = endIndex - period + 1;
        if (start <= 0) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i <= endIndex; i++) {
            KLine current = kLines.get(i);
            KLine prev = kLines.get(i - 1);
            BigDecimal tr1 = current.getHigh().subtract(current.getLow()).abs();
            BigDecimal tr2 = current.getHigh().subtract(prev.getClose()).abs();
            BigDecimal tr3 = current.getLow().subtract(prev.getClose()).abs();
            BigDecimal tr = tr1.max(tr2).max(tr3);
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }
}
