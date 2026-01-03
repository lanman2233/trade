package com.trade.quant.monitor;

import com.trade.quant.backtest.ClosedTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滚动 Expected Value (EV) 计算器
 *
 * 职责：
 * - 为每个策略维护最近 N 笔已平仓交易的滚动窗口
 * - 计算策略的滚动 EV、胜率、平均盈亏
 * - 支持序列化和状态恢复（用于崩溃重启）
 *
 * EV 计算公式：
 * EV = (WinRate × AvgWin) - ((1 - WinRate) × AvgLoss)
 *
 * 线程安全：使用 ConcurrentHashMap + synchronized 方法
 */
public class RollingEVCalculator {

    private static final Logger logger = LoggerFactory.getLogger(RollingEVCalculator.class);

    private final int windowSize;
    private final Map<String, Deque<ClosedTrade>> tradeHistory;

    /**
     * 创建 EV 计算器
     *
     * @param windowSize 滚动窗口大小（交易笔数），建议 100
     */
    public RollingEVCalculator(int windowSize) {
        this.windowSize = windowSize;
        this.tradeHistory = new ConcurrentHashMap<>();
        logger.debug("RollingEVCalculator 初始化，窗口大小: {}", windowSize);
    }

    /**
     * 添加一笔已平仓交易
     * 在每次平仓后调用此方法更新 EV 统计
     *
     * @param trade 已平仓交易
     */
    public synchronized void addTrade(ClosedTrade trade) {
        if (trade == null) {
            logger.warn("尝试添加 null 交易，已忽略");
            return;
        }

        String strategyId = trade.getStrategyId();
        Deque<ClosedTrade> trades = tradeHistory.computeIfAbsent(
            strategyId,
            k -> new LinkedList<>()
        );

        trades.addLast(trade);

        // 维护窗口大小，移除最旧的交易
        while (trades.size() > windowSize) {
            trades.removeFirst();
        }

        logger.trace("策略 {} 添加交易，当前窗口大小: {}", strategyId, trades.size());
    }

    /**
     * 计算策略的滚动指标
     *
     * @param strategyId 策略 ID
     * @return 滚动指标（如果无足够数据则返回空指标）
     */
    public RollingMetrics calculateMetrics(String strategyId) {
        Deque<ClosedTrade> trades = tradeHistory.get(strategyId);

        if (trades == null || trades.isEmpty()) {
            return RollingMetrics.empty();
        }

        List<ClosedTrade> tradeList = new ArrayList<>(trades);
        int totalTrades = tradeList.size();

        // 计算胜率
        long winCount = tradeList.stream()
            .filter(ClosedTrade::isWin)
            .count();
        BigDecimal winRate = totalTrades > 0
            ? BigDecimal.valueOf(winCount).divide(
                BigDecimal.valueOf(totalTrades),
                4,
                RoundingMode.HALF_UP
              )
            : BigDecimal.ZERO;

        // 计算平均盈利
        BigDecimal totalWin = tradeList.stream()
            .filter(ClosedTrade::isWin)
            .map(ClosedTrade::getNetPnl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgWin = winCount > 0
            ? totalWin.divide(
                BigDecimal.valueOf(winCount),
                2,
                RoundingMode.HALF_UP
              )
            : BigDecimal.ZERO;

        // 计算平均亏损（取绝对值）
        long lossCount = totalTrades - winCount;
        BigDecimal totalLoss = tradeList.stream()
            .filter(ClosedTrade::isLoss)
            .map(ClosedTrade::getNetPnl)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgLoss = lossCount > 0
            ? totalLoss.divide(
                BigDecimal.valueOf(lossCount),
                2,
                RoundingMode.HALF_UP
              )
            : BigDecimal.ZERO;

        // 计算 EV = (WinRate × AvgWin) - ((1 - WinRate) × AvgLoss)
        BigDecimal winFactor = winRate.multiply(avgWin);
        BigDecimal lossFactor = BigDecimal.ONE.subtract(winRate).multiply(avgLoss);
        BigDecimal rollingEV = winFactor.subtract(lossFactor)
            .setScale(2, RoundingMode.HALF_UP);

        // 计算连续亏损次数（从最新交易往回数）
        int consecutiveLosses = calculateConsecutiveLosses(tradeList);

        return new RollingMetrics(
            strategyId,
            totalTrades,
            rollingEV,
            winRate,
            avgWin,
            avgLoss,
            consecutiveLosses
        );
    }

    /**
     * 计算连续亏损次数
     * 从最新交易开始往回数，直到遇到第一笔盈利交易
     */
    private int calculateConsecutiveLosses(List<ClosedTrade> trades) {
        int count = 0;
        for (int i = trades.size() - 1; i >= 0; i--) {
            if (trades.get(i).isLoss()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * 清空策略的交易历史
     * 用于策略重置或重新启用
     *
     * @param strategyId 策略 ID
     */
    public void clearStrategy(String strategyId) {
        tradeHistory.remove(strategyId);
        logger.info("已清空策略 {} 的 EV 历史", strategyId);
    }

    /**
     * 获取可序列化的状态
     * 用于崩溃恢复或状态持久化
     *
     * @return strategyId -> 交易列表的映射
     */
    public Map<String, List<ClosedTrade>> getState() {
        Map<String, List<ClosedTrade>> state = new HashMap<>();
        tradeHistory.forEach((k, v) -> state.put(k, new ArrayList<>(v)));
        return state;
    }

    /**
     * 从持久化状态恢复
     * 用于系统重启后恢复 EV 计算器状态
     *
     * @param state 之前保存的状态
     */
    public void restoreState(Map<String, List<ClosedTrade>> state) {
        if (state == null || state.isEmpty()) {
            logger.info("无 EV 状态需要恢复");
            return;
        }

        state.forEach((k, v) -> {
            Deque<ClosedTrade> deque = new LinkedList<>(v);

            // 恢复后修剪到窗口大小（防止配置变更导致窗口变小）
            while (deque.size() > windowSize) {
                deque.removeFirst();
            }

            tradeHistory.put(k, deque);
        });

        logger.info("已恢复 {} 个策略的 EV 状态", state.size());
    }

    /**
     * 获取当前窗口大小
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * 获取跟踪的策略数量
     */
    public int getTrackedStrategyCount() {
        return tradeHistory.size();
    }
}
