package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 简单移动平均线（Simple Moving Average）
 */
public class SMA implements Indicator {

    private final int period;

    public SMA(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于0");
        }
        this.period = period;
    }

    @Override
    public List<BigDecimal> calculate(List<BigDecimal> prices) {
        if (prices.size() < period) {
            throw new IllegalArgumentException("价格数量不足，需要至少 " + period + " 个数据点");
        }

        List<BigDecimal> result = new ArrayList<>();

        for (int i = period - 1; i < prices.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < period; j++) {
                sum = sum.add(prices.get(i - j));
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            result.add(avg);
        }

        return result;
    }

    /**
     * 获取最新SMA值
     */
    public BigDecimal latest(List<BigDecimal> prices) {
        List<BigDecimal> smaValues = calculate(prices);
        return smaValues.get(smaValues.size() - 1);
    }

    @Override
    public String getName() {
        return "SMA-" + period;
    }
}
