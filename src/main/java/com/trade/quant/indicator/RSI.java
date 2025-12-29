package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 相对强弱指标（Relative Strength Index）
 */
public class RSI implements Indicator {

    private final int period;

    public RSI(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于0");
        }
        this.period = period;
    }

    @Override
    public List<BigDecimal> calculate(List<BigDecimal> prices) {
        if (prices.size() < period + 1) {
            throw new IllegalArgumentException("价格数量不足，需要至少 " + (period + 1) + " 个数据点");
        }

        List<BigDecimal> rsiValues = new ArrayList<>();

        // 计算价格变化
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();

        for (int i = 1; i < prices.size(); i++) {
            BigDecimal change = prices.get(i).subtract(prices.get(i - 1));
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gains.add(change);
                losses.add(BigDecimal.ZERO);
            } else {
                gains.add(BigDecimal.ZERO);
                losses.add(change.abs());
            }
        }

        // 初始平均涨跌幅
        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        for (int i = 0; i < period; i++) {
            avgGain = avgGain.add(gains.get(i));
            avgLoss = avgLoss.add(losses.get(i));
        }

        avgGain = avgGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // 初始RSI
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            rsiValues.add(BigDecimal.valueOf(100));
        } else {
            BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
            BigDecimal rsi = BigDecimal.valueOf(100).subtract(
                    BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP)
            );
            rsiValues.add(rsi);
        }

        // 后续使用Wilder平滑方法
        for (int i = period; i < gains.size(); i++) {
            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(gains.get(i))
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(losses.get(i))
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

            if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                rsiValues.add(BigDecimal.valueOf(100));
            } else {
                BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
                BigDecimal rsi = BigDecimal.valueOf(100).subtract(
                        BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP)
                );
                rsiValues.add(rsi);
            }
        }

        return rsiValues;
    }

    /**
     * 获取最新RSI值
     */
    public BigDecimal latest(List<BigDecimal> prices) {
        List<BigDecimal> rsiValues = calculate(prices);
        return rsiValues.get(rsiValues.size() - 1);
    }

    /**
     * 判断是否超买
     */
    public boolean isOverbought(List<BigDecimal> prices, BigDecimal threshold) {
        return latest(prices).compareTo(threshold) > 0;
    }

    /**
     * 判断是否超卖
     */
    public boolean isOversold(List<BigDecimal> prices, BigDecimal threshold) {
        return latest(prices).compareTo(threshold) < 0;
    }

    @Override
    public String getName() {
        return "RSI-" + period;
    }
}
