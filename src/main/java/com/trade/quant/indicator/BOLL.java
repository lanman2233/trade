package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 布林带（Bollinger Bands）
 */
public class BOLL {

    private final int period;
    private final BigDecimal stdDevMultiplier;

    public BOLL(int period, BigDecimal stdDevMultiplier) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于0");
        }
        if (stdDevMultiplier.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("标准差倍数必须大于0");
        }
        this.period = period;
        this.stdDevMultiplier = stdDevMultiplier;
    }

    public BOLL(int period) {
        this(period, BigDecimal.valueOf(2)); // 默认2倍标准差
    }

    /**
     * 布林带结果
     */
    public static class BOLLResult {
        public final BigDecimal upper;  // 上轨
        public final BigDecimal middle; // 中轨
        public final BigDecimal lower;  // 下轨
        public final BigDecimal bandwidth; // 带宽

        public BOLLResult(BigDecimal upper, BigDecimal middle, BigDecimal lower) {
            this.upper = upper;
            this.middle = middle;
            this.lower = lower;
            this.bandwidth = middle.subtract(lower); // 带宽
        }
    }

    /**
     * 计算布林带
     */
    public List<BOLLResult> calculate(List<BigDecimal> prices) {
        if (prices.size() < period) {
            throw new IllegalArgumentException("价格数量不足，需要至少 " + period + " 个数据点");
        }

        SMA sma = new SMA(period);
        List<BigDecimal> middleBand = sma.calculate(prices);

        List<BOLLResult> results = new ArrayList<>();

        for (int i = period - 1; i < prices.size(); i++) {
            // 计算中轨（SMA）
            BigDecimal middle = middleBand.get(i - period + 1);

            // 计算标准差
            BigDecimal sumSq = BigDecimal.ZERO;
            for (int j = 0; j < period; j++) {
                BigDecimal diff = prices.get(i - j).subtract(middle);
                sumSq = sumSq.add(diff.multiply(diff));
            }
            BigDecimal variance = sumSq.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            BigDecimal stdDev = sqrt(variance);

            // 计算上下轨
            BigDecimal upper = middle.add(stdDev.multiply(stdDevMultiplier));
            BigDecimal lower = middle.subtract(stdDev.multiply(stdDevMultiplier));

            results.add(new BOLLResult(upper, middle, lower));
        }

        return results;
    }

    /**
     * 获取最新布林带值
     */
    public BOLLResult latest(List<BigDecimal> prices) {
        List<BOLLResult> bollResults = calculate(prices);
        return bollResults.get(bollResults.size() - 1);
    }

    /**
     * 判断价格是否触及上轨
     */
    public boolean isTouchingUpper(List<BigDecimal> prices) {
        BOLLResult boll = latest(prices);
        BigDecimal currentPrice = prices.get(prices.size() - 1);
        return currentPrice.compareTo(boll.upper) >= 0;
    }

    /**
     * 判断价格是否触及下轨
     */
    public boolean isTouchingLower(List<BigDecimal> prices) {
        BOLLResult boll = latest(prices);
        BigDecimal currentPrice = prices.get(prices.size() - 1);
        return currentPrice.compareTo(boll.lower) <= 0;
    }

    /**
     * 判断是否收口（带宽缩小）
     */
    public boolean isSqueeze(List<BigDecimal> prices, BigDecimal threshold) {
        List<BOLLResult> results = calculate(prices);
        if (results.size() < 2) {
            return false;
        }

        BOLLResult prev = results.get(results.size() - 2);
        BOLLResult curr = results.get(results.size() - 1);

        BigDecimal prevBandwidth = prev.upper.subtract(prev.lower);
        BigDecimal currBandwidth = curr.upper.subtract(curr.lower);

        // 带宽缩小超过阈值百分比
        BigDecimal change = prevBandwidth.subtract(currBandwidth)
                .divide(prevBandwidth, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return change.compareTo(threshold) > 0;
    }

    /**
     * 计算标准差
     */
    private BigDecimal sqrt(BigDecimal value) {
        // 牛顿迭代法计算平方根
        BigDecimal x = value;
        BigDecimal x2;
        BigDecimal precision = BigDecimal.valueOf(0.00000001);

        while (true) {
            x2 = x.add(value.divide(x, 8, RoundingMode.HALF_UP))
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            if (x2.subtract(x).abs().compareTo(precision) < 0) {
                return x2;
            }
            x = x2;
        }
    }

    @Override
    public String toString() {
        return String.format("BOLL(%d,%.1f)", period, stdDevMultiplier);
    }
}
