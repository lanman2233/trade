package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 移动分位数跟踪器
 */
public class RollingPercentileTracker {

    private final int windowSize;
    private final List<BigDecimal> history;

    public RollingPercentileTracker(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口大小必须大于0");
        }
        this.windowSize = windowSize;
        this.history = new ArrayList<>(windowSize);
    }

    public void update(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("价格必须为正数");
        }
        history.add(value);
        if (history.size() > windowSize) {
            history.remove(0);
        }
    }

    public BigDecimal getPercentile() {
        if (history.isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> sorted = new ArrayList<>(history);
        Collections.sort(sorted);
        BigDecimal current = history.get(history.size() - 1);

        int rank = 0;
        for (BigDecimal value : sorted) {
            if (value.compareTo(current) <= 0) {
                rank++;
            }
        }

        return BigDecimal.valueOf(rank)
                .divide(BigDecimal.valueOf(sorted.size()), 4, RoundingMode.HALF_UP);
    }

    public int getSampleSize() {
        return history.size();
    }

    public int getWindowSize() {
        return windowSize;
    }

    public boolean hasEnoughSamples() {
        int threshold = (int) Math.ceil(windowSize * 0.8);
        return history.size() >= threshold;
    }

    public void reset() {
        history.clear();
    }
}
