package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 指数移动平均线（Exponential Moving Average）
 */
public class EMA implements Indicator {

    private final int period;
    private final BigDecimal multiplier;

    public EMA(int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("周期必须大于0");
        }
        this.period = period;
        // 平滑系数 = 2 / (period + 1)
        this.multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), 8, RoundingMode.HALF_UP);
    }

    @Override
    public List<BigDecimal> calculate(List<BigDecimal> prices) {
        if (prices.size() < period) {
            throw new IllegalArgumentException("价格数量不足，需要至少 " + period + " 个数据点");
        }

        List<BigDecimal> result = new ArrayList<>();

        // 初始EMA使用前period个价格的SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(prices.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        result.add(ema);

        // 后续使用EMA公式递推
        for (int i = period; i < prices.size(); i++) {
            BigDecimal price = prices.get(i);
            // EMA = (Price - EMA_prev) * multiplier + EMA_prev
            ema = price.subtract(ema).multiply(multiplier).add(ema)
                    .setScale(8, RoundingMode.HALF_UP);
            result.add(ema);
        }

        return result;
    }

    /**
     * 获取最新EMA值
     */
    public BigDecimal latest(List<BigDecimal> prices) {
        List<BigDecimal> emaValues = calculate(prices);
        return emaValues.get(emaValues.size() - 1);
    }

    @Override
    public String getName() {
        return "EMA-" + period;
    }
}
