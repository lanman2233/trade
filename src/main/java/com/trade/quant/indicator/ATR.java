package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 平均真实波幅（Average True Range）
 * 用于动态止损和仓位管理
 */
public class ATR implements Indicator {

    private final int period;

    public ATR(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于0");
        }
        this.period = period;
    }

    @Override
    public List<BigDecimal> calculate(List<BigDecimal> prices) {
        throw new UnsupportedOperationException("ATR需要OHLC数据，请使用 calculateWithHighLow 方法");
    }

    /**
     * 计算ATR（需要高低价数据）
     * @param highs 最高价序列
     * @param lows 最低价序列
     * @param closes 收盘价序列
     */
 public List<BigDecimal> calculateWithHighLow(List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes) {
        if (highs.size() < period + 1 || lows.size() < period + 1 || closes.size() < period + 1) {
            throw new IllegalArgumentException("数据不足，需要至少 " + (period + 1) + " 个数据点");
        }

        List<BigDecimal> trueRanges = new ArrayList<>();

        // 计算True Range
        for (int i = 1; i < highs.size(); i++) {
            BigDecimal high = highs.get(i);
            BigDecimal low = lows.get(i);
            BigDecimal prevClose = closes.get(i - 1);

            // TR = max(H-L, abs(H-PC), abs(L-PC))
            BigDecimal tr1 = high.subtract(low);
            BigDecimal tr2 = high.subtract(prevClose).abs();
            BigDecimal tr3 = low.subtract(prevClose).abs();

            BigDecimal tr = tr1.max(tr2).max(tr3);
            trueRanges.add(tr);
        }

        // 初始ATR使用前period个TR的SMA
        BigDecimal atr = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            atr = atr.add(trueRanges.get(i));
        }
        atr = atr.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        List<BigDecimal> atrValues = new ArrayList<>();
        atrValues.add(atr);

        // 后续使用EMA平滑
        for (int i = period; i < trueRanges.size(); i++) {
            // ATR = (ATR_prev * (period-1) + TR) / period
            atr = atr.multiply(BigDecimal.valueOf(period - 1))
                    .add(trueRanges.get(i))
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            atrValues.add(atr);
        }

        return atrValues;
    }

    /**
     * 获取最新ATR值
     */
    public BigDecimal latest(List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes) {
        List<BigDecimal> atrValues = calculateWithHighLow(highs, lows, closes);
        return atrValues.get(atrValues.size() - 1);
    }

    /**
     * 计算ATR止损位（多头）
     * @param entryPrice 开仓价
     * @param atrMultiplier ATR倍数
     */
    public BigDecimal calculateLongStopLoss(BigDecimal entryPrice, BigDecimal atr, BigDecimal atrMultiplier) {
        BigDecimal stopDistance = atr.multiply(atrMultiplier);
        return entryPrice.subtract(stopDistance);
    }

    /**
     * 计算ATR止损位（空头）
     * @param entryPrice 开仓价
     * @param atrMultiplier ATR倍数
     */
    public BigDecimal calculateShortStopLoss(BigDecimal entryPrice, BigDecimal atr, BigDecimal atrMultiplier) {
        BigDecimal stopDistance = atr.multiply(atrMultiplier);
        return entryPrice.add(stopDistance);
    }

    @Override
    public String getName() {
        return "ATR-" + period;
    }
}
