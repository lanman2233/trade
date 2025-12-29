package com.trade.quant.strategy.impl;

import com.trade.quant.core.*;
import com.trade.quant.strategy.*;
import com.trade.quant.indicator.SMA;

import java.math.BigDecimal;
import java.util.List;

/**
 * 双均线趋势跟随策略
 *
 * 逻辑：
 * - 短期均线上穿长期均线 -> 金叉，做多
 * - 短期均线下穿长期均线 -> 死叉，做空
 * - 必须有成交量确认
 */
public class DualMovingAverageStrategy extends AbstractStrategy {

    private final int fastPeriod;
    private final int slowPeriod;
    private final SMA fastSMA;
    private final SMA slowSMA;

    public DualMovingAverageStrategy(Symbol symbol, Interval interval,
                                     int fastPeriod, int slowPeriod,
                                     StrategyConfig config) {
        super("DualMA", symbol, interval, config);
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.fastSMA = new SMA(fastPeriod);
        this.slowSMA = new SMA(slowPeriod);
    }

    @Override
    public String getName() {
        return String.format("双均线策略(%d,%d)", fastPeriod, slowPeriod);
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        if (kLines.size() < slowPeriod + 1) {
            return null;
        }

        // 更新K线计数
        incrementBarCount();

        List<BigDecimal> closes = extractCloses(kLines);

        // 计算快慢均线
        List<BigDecimal> fastValues = fastSMA.calculate(closes);
        List<BigDecimal> slowValues = slowSMA.calculate(closes);

        // 获取最新的两个值
        int size = fastValues.size();
        BigDecimal fastNow = fastValues.get(size - 1);
        BigDecimal fastPrev = fastValues.get(size - 2);
        BigDecimal slowNow = slowValues.get(size - 1);
        BigDecimal slowPrev = slowValues.get(size - 2);

        // 检查金叉
        if (fastPrev.compareTo(slowPrev) <= 0 && fastNow.compareTo(slowNow) > 0) {
            // 需要成交量确认
            if (config.isRequireVolumeConfirmation() &&
                    !isVolumeHigh(kLines, 20, config.getMinVolumeRatio())) {
                return null;
            }

            // 计算仓位大小（固定1%风险）
            BigDecimal quantity = calculatePositionSize(kLines);

            recordTrade();
            return createLongSignal(kLines, quantity,
                    String.format("金叉入场: 快线%.2f > 慢线%.2f", fastNow, slowNow));
        }

        // 检查死叉
        if (fastPrev.compareTo(slowPrev) >= 0 && fastNow.compareTo(slowNow) < 0) {
            if (config.isRequireVolumeConfirmation() &&
                    !isVolumeHigh(kLines, 20, config.getMinVolumeRatio())) {
                return null;
            }

            BigDecimal quantity = calculatePositionSize(kLines);

            recordTrade();
            return createShortSignal(kLines, quantity,
                    String.format("死叉入场: 快线%.2f < 慢线%.2f", fastNow, slowNow));
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine) {
        // 简单出场：均线反向交叉
        // 实际使用中应该由风控模块管理止损
        return null;
    }

    /**
     * 计算仓位大小
     * 基于 1% 账户风险原则
     */
    private BigDecimal calculatePositionSize(List<KLine> kLines) {
        // TODO: 需要账户余额信息，这里暂时返回固定值
        // 实际应该由风控模块计算
        return BigDecimal.valueOf(0.01); // 0.01张（示例值）
    }
}
