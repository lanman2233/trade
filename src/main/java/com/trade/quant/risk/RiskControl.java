package com.trade.quant.risk;

import com.trade.quant.core.*;
import com.trade.quant.strategy.Signal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控模块
 *
 * 职责：
 * 1. 对策略信号进行最终检查
 * 2. 管理仓位大小
 * 3. 设置止损价格
 * 4. 监控回撤
 * 5. 控制连续亏损
 *
 * 重要：风控模块有最终否决权
 */
public class RiskControl {

    private final RiskConfig config;
    private final AccountInfo accountInfo;
    private final Map<String, Integer> consecutiveLosses;  // 连续亏损次数
    private final Map<String, BigDecimal> dailyLoss;       // 当日亏损
    private final Map<String, BigDecimal> peakEquity;      // 峰值权益
    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    public RiskControl(RiskConfig config, AccountInfo accountInfo) {
        this.config = config;
        this.accountInfo = accountInfo;
        this.consecutiveLosses = new ConcurrentHashMap<>();
        this.dailyLoss = new ConcurrentHashMap<>();
        this.peakEquity = new ConcurrentHashMap<>();
    }

    /**
     * 检查并验证信号
     * @return 验证后的订单，如果被拒绝返回null
     */
    public Order validateAndCreateOrder(Signal signal, List<Position> existingPositions) {
        // 1. 检查全局开关
        if (!config.isTradingEnabled()) {
            logRisk("交易已暂停", signal);
            return null;
        }

        // 2. 检查账户余额
        if (!checkBalance(signal)) {
            logRisk("余额不足", signal);
            return null;
        }

        // 3. 检查连续亏损限制
        if (!checkConsecutiveLosses(signal)) {
            logRisk("连续亏损达到上限", signal);
            return null;
        }

        // 4. 检查回撤限制
        if (!checkDrawdown(signal)) {
            logRisk("回撤超过上限", signal);
            return null;
        }

        // 5. 检查持仓限制
        if (!checkPositionLimit(signal, existingPositions)) {
            logRisk("持仓限制", signal);
            return null;
        }

        // 6. 计算并验证仓位大小
        BigDecimal adjustedQuantity = calculatePositionSize(signal, existingPositions);
        if (Decimal.isZero(adjustedQuantity)) {
            logRisk("计算仓位为0", signal);
            return null;
        }

        // 7. 验证止损
        if (!validateStopLoss(signal)) {
            logRisk("止损价格无效", signal);
            return null;
        }

        // 8. 创建订单
        Order order = signal.toOrder(config.getDefaultOrderType());
        // 更新仓位大小
        Order.Builder builder = Order.builder()
                .orderId(order.getOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .type(order.getType())
                .quantity(adjustedQuantity)
                .price(order.getPrice())
                .stopLoss(order.getStopLoss())
                .takeProfit(order.getTakeProfit())
                .strategyId(order.getStrategyId());

        // 如果使用市价单，清空价格
        if (order.getType() == OrderType.MARKET) {
            builder.price(BigDecimal.ZERO);
        }

        return builder.build();
    }

    /**
     * 检查余额是否充足
     */
    private boolean checkBalance(Signal signal) {
        BigDecimal available = accountInfo.getAvailableBalance();
        BigDecimal required = signal.getPrice().multiply(signal.getQuantity());

        // 需要预留保证金
        BigDecimal marginBuffer = required.multiply(config.getMarginBuffer());

        return available.compareTo(marginBuffer) > 0;
    }

    /**
     * 检查连续亏损限制
     */
    private boolean checkConsecutiveLosses(Signal signal) {
        String key = signal.getStrategyId();
        int losses = consecutiveLosses.getOrDefault(key, 0);

        return losses < config.getMaxConsecutiveLosses();
    }

    /**
     * 检查回撤限制
     */
    private boolean checkDrawdown(Signal signal) {
        String key = signal.getStrategyId();
        BigDecimal currentEquity = accountInfo.getTotalBalance();

        // 更新峰值
        BigDecimal peak = peakEquity.getOrDefault(key, currentEquity);
        if (currentEquity.compareTo(peak) > 0) {
            peakEquity.put(key, currentEquity);
            peak = currentEquity;
        }

        // 计算回撤
        BigDecimal drawdown = peak.subtract(currentEquity)
                .divide(peak, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return drawdown.compareTo(config.getMaxDrawdownPercent()) < 0;
    }

    /**
     * 检查持仓限制
     */
    private boolean checkPositionLimit(Signal signal, List<Position> existingPositions) {
        // 检查同策略持仓数量
        long sameStrategyPositions = existingPositions.stream()
                .filter(p -> p.getSymbol().equals(signal.getSymbol()))
                .count();

        return sameStrategyPositions < config.getMaxPositionsPerSymbol();
    }

    /**
     * 计算仓位大小
     * 基于 1-2% 账户风险原则
     */
    private BigDecimal calculatePositionSize(Signal signal, List<Position> existingPositions) {
        BigDecimal accountBalance = accountInfo.getTotalBalance();
        BigDecimal riskAmount = accountBalance.multiply(config.getRiskPerTrade());

        // 计算止损距离
        BigDecimal entryPrice = signal.getPrice();
        BigDecimal stopLoss = signal.getStopLoss();
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss).abs();

        if (Decimal.isZero(riskPerUnit)) {
            return BigDecimal.ZERO;
        }

        // 仓位 = 风险金额 / 每单位风险
        BigDecimal positionSize = riskAmount.divide(riskPerUnit, 3, java.math.RoundingMode.DOWN);

        // 检查最大仓位限制
        BigDecimal maxPosition = accountBalance.multiply(config.getMaxPositionRatio());
        positionSize = positionSize.min(maxPosition);

        return Decimal.scaleQuantity(positionSize);
    }

    /**
     * 验证止损
     */
    private boolean validateStopLoss(Signal signal) {
        if (signal.getStopLoss() == null || signal.getStopLoss().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // 检查止损距离是否合理
        BigDecimal entryPrice = signal.getPrice();
        BigDecimal stopLoss = signal.getStopLoss();
        BigDecimal distance = entryPrice.subtract(stopLoss).abs()
                .divide(entryPrice, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return distance.compareTo(config.getMaxStopLossPercent()) <= 0;
    }

    /**
     * 记录交易结果（用于风控学习）
     */
    public void recordTradeResult(String orderId, boolean isWin, BigDecimal pnl) {
        // 更新统计
        String strategyId = orderId.substring(0, orderId.indexOf("-")); // 简单提取

        if (isWin) {
            consecutiveLosses.put(strategyId, 0);
        } else {
            int losses = consecutiveLosses.getOrDefault(strategyId, 0) + 1;
            consecutiveLosses.put(strategyId, losses);
        }
    }

    /**
     * 紧急停止交易
     */
    public void emergencyStop() {
        config.setTradingEnabled(false);
    }

    /**
     * 恢复交易
     */
    public void resumeTrading() {
        config.setTradingEnabled(true);
        consecutiveLosses.clear();
        dailyLoss.clear();
    }

    /**
     * 获取当前风险状态
     */
    public RiskStatus getRiskStatus() {
        return new RiskStatus(
                config.isTradingEnabled(),
                maxDrawdown,
                consecutiveLosses,
                dailyLoss
        );
    }

    private void logRisk(String reason, Signal signal) {
        System.err.println(String.format("[风控拒绝] %s - 策略: %s, 原因: %s",
                signal.getSymbol(), signal.getStrategyId(), reason));
    }
}
