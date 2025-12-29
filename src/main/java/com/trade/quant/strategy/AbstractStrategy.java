package com.trade.quant.strategy;

import com.trade.quant.core.*;
import com.trade.quant.indicator.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 策略抽象基类
 * 提供常用的工具方法和默认实现
 */
public abstract class AbstractStrategy implements Strategy {

    protected final String strategyId;
    protected final Symbol symbol;
    protected final Interval interval;
    protected StrategyConfig config;
    protected Instant lastTradeTime;
    protected int barsSinceLastTrade;

    protected AbstractStrategy(String name, Symbol symbol, Interval interval, StrategyConfig config) {
        this.strategyId = name + "-" + symbol.toPairString() + "-" + interval.getCode();
        this.symbol = symbol;
        this.interval = interval;
        this.config = config != null ? config : StrategyConfig.builder().build();
        this.barsSinceLastTrade = Integer.MAX_VALUE; // 初始不在冷却期
    }

    @Override
    public String getStrategyId() {
        return strategyId;
    }

    @Override
    public Symbol getSymbol() {
        return symbol;
    }

    @Override
    public Interval getInterval() {
        return interval;
    }

    @Override
    public StrategyConfig getConfig() {
        return config;
    }

    @Override
    public void setConfig(StrategyConfig config) {
        this.config = config;
    }

    @Override
    public boolean isInCooldown() {
        return barsSinceLastTrade < config.getCooldownBars();
    }

    @Override
    public long getCooldownRemaining() {
        if (barsSinceLastTrade >= config.getCooldownBars()) {
            return 0;
        }
        long remainingBars = config.getCooldownBars() - barsSinceLastTrade;
        return remainingBars * interval.getMinutes() * 60 * 1000L;
    }

    @Override
    public void reset() {
        lastTradeTime = null;
        barsSinceLastTrade = Integer.MAX_VALUE;
    }

    /**
     * 记录交易（更新冷却状态）
     */
    protected void recordTrade() {
        lastTradeTime = Instant.now();
        barsSinceLastTrade = 0;
    }

    /**
     * 增加K线计数
     */
    public void incrementBarCount() {
        if (barsSinceLastTrade < Integer.MAX_VALUE) {
            barsSinceLastTrade++;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 提取收盘价序列
     */
    protected List<BigDecimal> extractCloses(List<KLine> kLines) {
        return kLines.stream()
                .map(KLine::getClose)
                .toList();
    }

    /**
     * 提取最高价序列
     */
    protected List<BigDecimal> extractHighs(List<KLine> kLines) {
        return kLines.stream()
                .map(KLine::getHigh)
                .toList();
    }

    /**
     * 提取最低价序列
     */
    protected List<BigDecimal> extractLows(List<KLine> kLines) {
        return kLines.stream()
                .map(KLine::getLow)
                .toList();
    }

    /**
     * 获取最新价格
     */
    protected BigDecimal getLatestPrice(List<KLine> kLines) {
        return kLines.get(kLines.size() - 1).getClose();
    }

    /**
     * 检查成交量是否放大
     */
    protected boolean isVolumeHigh(List<KLine> kLines, int period, BigDecimal ratio) {
        if (kLines.size() < period + 1) {
            return false;
        }

        BigDecimal currentVolume = kLines.get(kLines.size() - 1).getVolume();

        BigDecimal avgVolume = BigDecimal.ZERO;
        for (int i = 2; i <= period + 1; i++) {
            avgVolume = avgVolume.add(kLines.get(kLines.size() - i).getVolume());
        }
        avgVolume = avgVolume.divide(BigDecimal.valueOf(period), 8, java.math.RoundingMode.HALF_UP);

        return currentVolume.compareTo(avgVolume.multiply(ratio)) > 0;
    }

    /**
     * 计算ATR止损
     */
    protected BigDecimal calculateATRStopLoss(List<KLine> kLines, Side side) {
        ATR atr = new ATR(14);
        List<BigDecimal> highs = extractHighs(kLines);
        List<BigDecimal> lows = extractLows(kLines);
        List<BigDecimal> closes = extractCloses(kLines);

        BigDecimal latestATR = atr.latest(highs, lows, closes);
        BigDecimal entryPrice = getLatestPrice(kLines);

        if (side == Side.BUY) {
            return atr.calculateLongStopLoss(entryPrice, latestATR, config.getAtrStopLossMultiplier());
        } else {
            return atr.calculateShortStopLoss(entryPrice, latestATR, config.getAtrStopLossMultiplier());
        }
    }

    /**
     * 创建做多信号
     */
    protected Signal createLongSignal(List<KLine> kLines, BigDecimal quantity, String reason) {
        BigDecimal price = getLatestPrice(kLines);
        BigDecimal stopLoss = config.isUseATRStopLoss()
                ? calculateATRStopLoss(kLines, Side.BUY)
                : BigDecimal.ZERO;

        return new Signal(
                strategyId,
                symbol,
                SignalType.ENTRY_LONG,
                Side.BUY,
                price,
                quantity,
                stopLoss,
                BigDecimal.ZERO, // 止盈由策略决定
                reason
        );
    }

    /**
     * 创建做空信号
     */
    protected Signal createShortSignal(List<KLine> kLines, BigDecimal quantity, String reason) {
        BigDecimal price = getLatestPrice(kLines);
        BigDecimal stopLoss = config.isUseATRStopLoss()
                ? calculateATRStopLoss(kLines, Side.SELL)
                : BigDecimal.ZERO;

        return new Signal(
                strategyId,
                symbol,
                SignalType.ENTRY_SHORT,
                Side.SELL,
                price,
                quantity,
                stopLoss,
                BigDecimal.ZERO,
                reason
        );
    }

    /**
     * 创建出场信号
     */
    protected Signal createExitSignal(PositionSide side, BigDecimal quantity, String reason) {
        return new Signal(
                strategyId,
                symbol,
                side == PositionSide.LONG ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT,
                side == PositionSide.LONG ? Side.SELL : Side.BUY,
                BigDecimal.ZERO, // 市价出场
                quantity,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                reason
        );
    }
}
