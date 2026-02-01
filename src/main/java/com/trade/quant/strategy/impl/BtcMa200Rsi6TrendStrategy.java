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
import com.trade.quant.indicator.ATR;
import com.trade.quant.indicator.RSI;
import com.trade.quant.indicator.SMA;
import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.TradeMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Simple trend + RSI6 strategy on 15m:
 * - If close > SMA200: only long when RSI6 < oversold
 * - If close < SMA200: only short when RSI6 > overbought
 * Stop loss: 3 * ATR(14)
 * Take profit: 5 * ATR(14)
 * Volatility filter: ATR/close >= 0.005
 */
public class BtcMa200Rsi6TrendStrategy extends AbstractStrategy implements BacktestTradeListener {

    private final SMA sma200;
    private final RSI rsi6;
    private final ATR atr14;

    private final BigDecimal baseMargin;
    private final BigDecimal leverage;
    private final BigDecimal takeProfitAtrMultiplier;
    private final BigDecimal atrStopMultiplier;
    private final BigDecimal rsiOverbought;
    private final BigDecimal rsiOversold;
    private final BigDecimal minAtrPct;

    private int processedBars;
    private final Deque<BigDecimal> smaWindow;
    private BigDecimal smaSum;
    private BigDecimal currentSma;

    private BigDecimal prevClose;
    private int rsiCount;
    private BigDecimal rsiAvgGain;
    private BigDecimal rsiAvgLoss;
    private BigDecimal currentRsi;

    private int atrCount;
    private BigDecimal atrSum;
    private BigDecimal currentAtr;
    private BigDecimal entryAtr;

    public BtcMa200Rsi6TrendStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("BTC-MA200-RSI6", symbol, interval, config);
        this.sma200 = new SMA(200);
        this.rsi6 = new RSI(6);
        this.atr14 = new ATR(14);

        ConfigManager cfg = ConfigManager.getInstance();
        this.baseMargin = cfg.getBigDecimalProperty("btc.simple.base.margin", BigDecimal.valueOf(1000));
        this.leverage = cfg.getBigDecimalProperty("btc.simple.leverage", BigDecimal.valueOf(10));
        this.takeProfitAtrMultiplier = cfg.getBigDecimalProperty("btc.simple.tp.atr.multiplier", new BigDecimal("5.0"));
        this.atrStopMultiplier = cfg.getBigDecimalProperty("btc.simple.atr.multiplier", new BigDecimal("3.0"));
        this.rsiOverbought = cfg.getBigDecimalProperty("btc.simple.rsi.overbought", new BigDecimal("70"));
        this.rsiOversold = cfg.getBigDecimalProperty("btc.simple.rsi.oversold", new BigDecimal("30"));
        this.minAtrPct = cfg.getBigDecimalProperty("btc.simple.min.atr.pct", new BigDecimal("0.006"));

        this.smaWindow = new ArrayDeque<>();
        resetIndicatorState();
    }

    @Override
    public String getName() {
        return "BTC MA200 + RSI6 Trend";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        if (kLines == null || kLines.size() < 200) {
            return null;
        }

        updateIndicators(kLines);
        if (currentSma == null || currentRsi == null || currentAtr == null) {
            return null;
        }

        BigDecimal close = kLines.get(kLines.size() - 1).getClose();

        if (close.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal atrPct = currentAtr.divide(close, 8, RoundingMode.HALF_UP);
        if (atrPct.compareTo(minAtrPct) < 0) {
            return null;
        }
        TradeMetrics metrics = new TradeMetrics(atrPct, currentRsi, null, currentSma);

        BigDecimal notional = baseMargin.multiply(leverage);
        BigDecimal quantity = notional.divide(close, 6, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        if (close.compareTo(currentSma) > 0 && currentRsi.compareTo(rsiOversold) < 0) {
            BigDecimal stopLoss = close.subtract(currentAtr.multiply(atrStopMultiplier));
            BigDecimal takeProfit = close.add(currentAtr.multiply(takeProfitAtrMultiplier));
            return new Signal(
                    strategyId,
                    symbol,
                    SignalType.ENTRY_LONG,
                    Side.BUY,
                    close,
                    quantity,
                    stopLoss,
                    takeProfit,
                    "trend_long_rsi",
                    metrics,
                    null
            );
        }

        if (close.compareTo(currentSma) < 0 && currentRsi.compareTo(rsiOverbought) > 0) {
            BigDecimal stopLoss = close.add(currentAtr.multiply(atrStopMultiplier));
            BigDecimal takeProfit = close.subtract(currentAtr.multiply(takeProfitAtrMultiplier));
            return new Signal(
                    strategyId,
                    symbol,
                    SignalType.ENTRY_SHORT,
                    Side.SELL,
                    close,
                    quantity,
                    stopLoss,
                    takeProfit,
                    "trend_short_rsi",
                    metrics,
                    null
            );
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine, List<KLine> allKLines) {
        updateIndicators(allKLines);
        BigDecimal high = currentKLine.getHigh();
        BigDecimal low = currentKLine.getLow();
        BigDecimal entry = position.getEntryPrice();
        BigDecimal atrForTp = entryAtr != null ? entryAtr : currentAtr;

        if (atrForTp == null) {
            return null;
        }

        if (position.getSide() == PositionSide.LONG) {
            BigDecimal takeProfit = entry.add(atrForTp.multiply(takeProfitAtrMultiplier));
            if (high.compareTo(takeProfit) >= 0) {
                return exitSignal(position, takeProfit, ExitReason.TAKE_PROFIT, "take_profit");
            }
        } else {
            BigDecimal takeProfit = entry.subtract(atrForTp.multiply(takeProfitAtrMultiplier));
            if (low.compareTo(takeProfit) <= 0) {
                return exitSignal(position, takeProfit, ExitReason.TAKE_PROFIT, "take_profit");
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
        smaWindow.clear();
        smaSum = BigDecimal.ZERO;
        currentSma = null;
        prevClose = null;
        rsiCount = 0;
        rsiAvgGain = BigDecimal.ZERO;
        rsiAvgLoss = BigDecimal.ZERO;
        currentRsi = null;
        atrCount = 0;
        atrSum = BigDecimal.ZERO;
        currentAtr = null;
        entryAtr = null;
    }

    private void updateIndicators(List<KLine> kLines) {
        for (int i = processedBars; i < kLines.size(); i++) {
            KLine k = kLines.get(i);
            BigDecimal close = k.getClose();

            // SMA 200
            smaWindow.addLast(close);
            smaSum = smaSum.add(close);
            if (smaWindow.size() > 200) {
                smaSum = smaSum.subtract(smaWindow.removeFirst());
            }
            if (smaWindow.size() == 200) {
                currentSma = smaSum.divide(BigDecimal.valueOf(200), 8, RoundingMode.HALF_UP);
            }

            if (prevClose != null) {
                BigDecimal change = close.subtract(prevClose);
                BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
                BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

                if (rsiCount < 6) {
                    rsiAvgGain = rsiAvgGain.add(gain);
                    rsiAvgLoss = rsiAvgLoss.add(loss);
                    rsiCount++;
                    if (rsiCount == 6) {
                        rsiAvgGain = rsiAvgGain.divide(BigDecimal.valueOf(6), 8, RoundingMode.HALF_UP);
                        rsiAvgLoss = rsiAvgLoss.divide(BigDecimal.valueOf(6), 8, RoundingMode.HALF_UP);
                        currentRsi = calculateRsiValue(rsiAvgGain, rsiAvgLoss);
                    }
                } else {
                    rsiAvgGain = rsiAvgGain.multiply(BigDecimal.valueOf(5))
                            .add(gain)
                            .divide(BigDecimal.valueOf(6), 8, RoundingMode.HALF_UP);
                    rsiAvgLoss = rsiAvgLoss.multiply(BigDecimal.valueOf(5))
                            .add(loss)
                            .divide(BigDecimal.valueOf(6), 8, RoundingMode.HALF_UP);
                    currentRsi = calculateRsiValue(rsiAvgGain, rsiAvgLoss);
                }

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

                if (atrCount < 14) {
                    atrSum = atrSum.add(tr);
                    atrCount++;
                    if (atrCount == 14) {
                        currentAtr = atrSum.divide(BigDecimal.valueOf(14), 8, RoundingMode.HALF_UP);
                    }
                } else {
                    currentAtr = currentAtr.multiply(BigDecimal.valueOf(13))
                            .add(tr)
                            .divide(BigDecimal.valueOf(14), 8, RoundingMode.HALF_UP);
                }
            }

            prevClose = close;
            processedBars++;
        }
    }

    private BigDecimal calculateRsiValue(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP)
        );
    }

    private Signal exitSignal(Position position, BigDecimal price, ExitReason reason, String msg) {
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
                reason
        );
    }

    @Override
    public void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
        if (metrics != null && metrics.atrPct() != null) {
            entryAtr = metrics.atrPct().multiply(position.getEntryPrice());
        } else {
            entryAtr = null;
        }
    }

    @Override
    public void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
        entryAtr = null;
    }
}
