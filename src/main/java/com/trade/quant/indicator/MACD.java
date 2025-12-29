package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 平滑异同移动平均线（Moving Average Convergence Divergence）
 */
public class MACD {

    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;

    public MACD(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException("快速周期必须小于慢速周期");
        }
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.signalPeriod = signalPeriod;
    }

    /**
     * MACD结果
     */
    public static class MACDResult {
        public final BigDecimal macdLine;
        public final BigDecimal signalLine;
        public final BigDecimal histogram;

        public MACDResult(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {
            this.macdLine = macdLine;
            this.signalLine = signalLine;
            this.histogram = histogram;
        }
    }

    /**
     * 计算MACD
     */
    public List<MACDResult> calculate(List<BigDecimal> prices) {
        if (prices.size() < slowPeriod + signalPeriod) {
            throw new IllegalArgumentException("价格数量不足");
        }

        // 计算快速EMA和慢速EMA
        EMA fastEma = new EMA(fastPeriod);
        EMA slowEma = new EMA(slowPeriod);

        List<BigDecimal> fastEmaValues = fastEma.calculate(prices);
        List<BigDecimal> slowEmaValues = slowEma.calculate(prices);

        // 对齐数据（slowEmaValues更短，从slowPeriod-1开始）
        int offset = (slowPeriod - 1) - (fastPeriod - 1);
        List<BigDecimal> alignedFastEma = new ArrayList<>(
                fastEmaValues.subList(offset, fastEmaValues.size()));

        // 计算MACD线
        List<BigDecimal> macdLine = new ArrayList<>();
        for (int i = 0; i < alignedFastEma.size(); i++) {
            macdLine.add(alignedFastEma.get(i).subtract(slowEmaValues.get(i)));
        }

        // 计算信号线（MACD的EMA）
        EMA signalEma = new EMA(signalPeriod);
        List<BigDecimal> signalLine = signalEma.calculate(macdLine);

        // 对齐MACD线和信号线
        int signalOffset = (signalPeriod - 1);
        List<BigDecimal> alignedMacdLine = new ArrayList<>(
                macdLine.subList(signalOffset, macdLine.size()));

        // 计算柱状图
        List<MACDResult> results = new ArrayList<>();
        for (int i = 0; i < alignedMacdLine.size(); i++) {
            BigDecimal macd = alignedMacdLine.get(i);
            BigDecimal signal = signalLine.get(i);
            BigDecimal histogram = macd.subtract(signal);
            results.add(new MACDResult(macd, signal, histogram));
        }

        return results;
    }

    /**
     * 获取最新MACD值
     */
    public MACDResult latest(List<BigDecimal> prices) {
        List<MACDResult> macdResults = calculate(prices);
        return macdResults.get(macdResults.size() - 1);
    }

    /**
     * 判断是否金叉（MACD线上穿信号线）
     */
    public boolean isGoldenCross(List<BigDecimal> prices) {
        List<MACDResult> results = calculate(prices);
        if (results.size() < 2) {
            return false;
        }

        MACDResult prev = results.get(results.size() - 2);
        MACDResult curr = results.get(results.size() - 1);

        return prev.histogram.compareTo(BigDecimal.ZERO) <= 0
                && curr.histogram.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 判断是否死叉（MACD线下穿信号线）
     */
    public boolean isDeathCross(List<BigDecimal> prices) {
        List<MACDResult> results = calculate(prices);
        if (results.size() < 2) {
            return false;
        }

        MACDResult prev = results.get(results.size() - 2);
        MACDResult curr = results.get(results.size() - 1);

        return prev.histogram.compareTo(BigDecimal.ZERO) >= 0
                && curr.histogram.compareTo(BigDecimal.ZERO) < 0;
    }

    @Override
    public String toString() {
        return String.format("MACD(%d,%d,%d)", fastPeriod, slowPeriod, signalPeriod);
    }
}
