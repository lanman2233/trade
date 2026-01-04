package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ATR 分位数跟踪器
 *
 * <p>用于维护历史 ATR 值的滑动窗口，并计算当前 ATR 在历史分布中的分位数。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>HFVS 策略判断市场波动率是否适中</li>
 *   <li>避免在波动率过低（无趋势）或过高（风险过大）时交易</li>
 * </ul>
 *
 * <p>示例：</p>
 * <pre>{@code
 * ATRPercentileTracker tracker = new ATRPercentileTracker(100);
 * tracker.updateATR(new BigDecimal("150.5"));
 * BigDecimal percentile = tracker.getPercentile();  // 0.0 ~ 1.0
 * boolean isModerate = percentile.compareTo(new BigDecimal("0.3")) >= 0 &&
 *                      percentile.compareTo(new BigDecimal("0.7")) <= 0;
 * }</pre>
 */
public class ATRPercentileTracker {

    private final int windowSize;          // 滑动窗口大小
    private final List<BigDecimal> atrHistory;  // ATR 历史记录

    /**
     * 构造 ATR 分位数跟踪器
     *
     * @param windowSize 滑动窗口大小（必须大于0）
     * @throws IllegalArgumentException 如果窗口大小 <= 0
     */
    public ATRPercentileTracker(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口大小必须大于0");
        }
        this.windowSize = windowSize;
        this.atrHistory = new ArrayList<>(windowSize);
    }

    /**
     * 更新 ATR 值（自动维护滑动窗口）
     *
     * <p>当历史记录超过窗口大小时，自动移除最旧的值。</p>
     *
     * @param atr 最新的 ATR 值（必须为正数）
     * @throws IllegalArgumentException 如果 ATR <= 0
     */
    public void updateATR(BigDecimal atr) {
        if (atr.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ATR 必须为正数");
        }

        atrHistory.add(atr);

        // 维护窗口大小
        if (atrHistory.size() > windowSize) {
            atrHistory.remove(0);  // 移除最旧的值
        }
    }

    /**
     * 获取当前 ATR 在历史分布中的分位数
     *
     * <p>分位数范围：0.0 ~ 1.0</p>
     * <p>0.5 表示中位数，当前 ATR 处于历史中位水平</p>
     *
     * @return 分位数（0.0 ~ 1.0），如果无历史数据返回 0.0
     */
    public BigDecimal getPercentile() {
        if (atrHistory.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 创建排序副本
        List<BigDecimal> sorted = new ArrayList<>(atrHistory);
        Collections.sort(sorted);

        // 获取当前 ATR（最新值）
        BigDecimal currentATR = atrHistory.get(atrHistory.size() - 1);

        // 计算小于等于当前 ATR 的样本数
        int rank = 0;
        for (BigDecimal value : sorted) {
            if (value.compareTo(currentATR) <= 0) {
                rank++;
            }
        }

        // 分位数 = rank / total
        BigDecimal percentile = BigDecimal.valueOf(rank)
            .divide(BigDecimal.valueOf(sorted.size()), 4, RoundingMode.HALF_UP);

        return percentile;
    }

    /**
     * 检查是否有足够的样本
     *
     * @return true 如果样本数 >= 窗口大小的 80%
     */
    public boolean hasEnoughSamples() {
        int threshold = (int) Math.ceil(windowSize * 0.8);
        return atrHistory.size() >= threshold;
    }

    /**
     * 获取当前 ATR 值
     *
     * @return 当前 ATR，如果无历史数据返回 ZERO
     */
    public BigDecimal getCurrentATR() {
        if (atrHistory.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return atrHistory.get(atrHistory.size() - 1);
    }

    /**
     * 获取 ATR 历史记录
     *
     * @return ATR 历史记录的副本
     */
    public List<BigDecimal> getHistory() {
        return new ArrayList<>(atrHistory);
    }

    /**
     * 获取当前样本数量
     *
     * @return 当前样本数量
     */
    public int getSampleSize() {
        return atrHistory.size();
    }

    /**
     * 获取窗口大小
     *
     * @return 窗口大小
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * 重置跟踪器
     */
    public void reset() {
        atrHistory.clear();
    }

    @Override
    public String toString() {
        return String.format("ATRPercentileTracker{window=%d, samples=%d, currentATR=%s, percentile=%s}",
            windowSize, atrHistory.size(), getCurrentATR(), getPercentile());
    }
}
