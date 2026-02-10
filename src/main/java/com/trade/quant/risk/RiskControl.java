package com.trade.quant.risk;

import com.trade.quant.core.*;
import com.trade.quant.strategy.Signal;
import com.trade.quant.core.ConfigManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控模块
 * 1. 最终风控检查
 * 2. 仓位管理
 * 3. 止损校验
 * 4. 回撤与连亏控制
 */
public class RiskControl {

    private record BalanceCheckResult(
            boolean passed,
            BigDecimal available,
            BigDecimal required,
            BigDecimal quantity,
            BigDecimal notional
    ) {}

    private final RiskConfig config;
    private volatile AccountInfo accountInfo;
    private final Map<String, Integer> consecutiveLosses;
    private final Map<String, BigDecimal> dailyLoss;
    private final Map<String, BigDecimal> peakEquity;
    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    public RiskControl(RiskConfig config, AccountInfo accountInfo) {
        this.config = config;
        this.accountInfo = accountInfo;
        this.consecutiveLosses = new ConcurrentHashMap<>();
        this.dailyLoss = new ConcurrentHashMap<>();
        this.peakEquity = new ConcurrentHashMap<>();
    }

    public void updateAccountInfo(AccountInfo accountInfo) {
        if (accountInfo != null) {
            this.accountInfo = accountInfo;
        }
    }

    /**
     * 检查并验证信号
     * @return 验证后的订单，若被拒绝返回 null
     */
    public Order validateAndCreateOrder(Signal signal, List<Position> existingPositions) {
        if (existingPositions == null) {
            existingPositions = List.of();
        }
        if (signal.isExit()) {
            BigDecimal closableQuantity = resolveClosableQuantity(signal, existingPositions);
            if (Decimal.isZero(closableQuantity)) {
                logRisk("无可平仓位", signal);
                return null;
            }
            BigDecimal requestedQuantity = signal.getQuantity();
            BigDecimal exitQuantity = (requestedQuantity == null || requestedQuantity.compareTo(BigDecimal.ZERO) <= 0)
                    ? closableQuantity
                    : requestedQuantity.min(closableQuantity);
            if (Decimal.isZero(exitQuantity)) {
                logRisk("平仓数量无效", signal);
                return null;
            }
            return buildOrderFromSignal(signal, exitQuantity, true);
        }

        if (!config.isTradingEnabled()) {
            logRisk("交易已暂停", signal);
            return null;
        }

        if (!checkConsecutiveLosses(signal)) {
            logRisk("连续亏损达到上限", signal);
            return null;
        }

        if (!checkDrawdown(signal)) {
            logRisk("回撤超过上限", signal);
            return null;
        }

        if (!checkPositionLimit(signal, existingPositions)) {
            logRisk("持仓限制", signal);
            return null;
        }

        BigDecimal adjustedQuantity = calculatePositionSize(signal, existingPositions);
        if (Decimal.isZero(adjustedQuantity)) {
            logRisk("计算仓位为0", signal);
            return null;
        }

        BalanceCheckResult balanceCheck = checkBalance(signal, adjustedQuantity);
        if (!balanceCheck.passed()) {
            logRisk(
                    "余额不足",
                    signal,
                    String.format(
                            "available=%s, required=%s, qty=%s, notional=%s",
                            balanceCheck.available().toPlainString(),
                            balanceCheck.required().toPlainString(),
                            balanceCheck.quantity().toPlainString(),
                            balanceCheck.notional().toPlainString()
                    )
            );
            return null;
        }

        if (!validateStopLoss(signal)) {
            logRisk("止损价格无效", signal);
            return null;
        }

        return buildOrderFromSignal(signal, adjustedQuantity, false);
    }

    private Order buildOrderFromSignal(Signal signal, BigDecimal quantity, boolean reduceOnly) {
        OrderType orderType = config.getDefaultOrderType();
        BigDecimal orderPrice = orderType == OrderType.MARKET ? BigDecimal.ZERO : signal.getPrice();
        BigDecimal stopLoss = signal.getStopLoss() != null ? signal.getStopLoss() : BigDecimal.ZERO;
        return Order.builder()
                .orderId(java.util.UUID.randomUUID().toString())
                .symbol(signal.getSymbol())
                .side(signal.getSide())
                .type(orderType)
                .quantity(quantity)
                .price(orderPrice)
                .stopLoss(stopLoss)
                .takeProfit(signal.getTakeProfit())
                .strategyId(signal.getStrategyId())
                .reduceOnly(reduceOnly)
                .build();
    }

    /**
     * 检查余额是否充足
     */
    private BalanceCheckResult checkBalance(Signal signal, BigDecimal quantity) {
        if (accountInfo == null || signal.getPrice() == null) {
            return new BalanceCheckResult(
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Decimal.scaleQuantity(quantity == null ? BigDecimal.ZERO : quantity),
                    BigDecimal.ZERO
            );
        }
        BigDecimal available = accountInfo.getAvailableBalance();
        BigDecimal notional = signal.getPrice().multiply(quantity);

        BigDecimal leverage = config.getLeverage();
        if (leverage == null || leverage.compareTo(BigDecimal.ZERO) <= 0) {
            leverage = BigDecimal.ONE;
        }
        BigDecimal requiredMargin = notional.divide(leverage, 8, RoundingMode.HALF_UP);
        BigDecimal requiredWithBuffer = requiredMargin.multiply(config.getMarginBuffer());

        return new BalanceCheckResult(
                available.compareTo(requiredWithBuffer) > 0,
                Decimal.scalePrice(available),
                Decimal.scalePrice(requiredWithBuffer),
                Decimal.scaleQuantity(quantity),
                Decimal.scalePrice(notional)
        );
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
        if (accountInfo == null) {
            return false;
        }
        String key = signal.getStrategyId();
        BigDecimal currentEquity = accountInfo.getTotalBalance();

        BigDecimal peak = peakEquity.getOrDefault(key, currentEquity);
        if (currentEquity.compareTo(peak) > 0) {
            peakEquity.put(key, currentEquity);
            peak = currentEquity;
        }

        BigDecimal drawdown = peak.subtract(currentEquity)
                .divide(peak, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return drawdown.compareTo(config.getMaxDrawdownPercent()) < 0;
    }

    /**
     * 检查持仓限制
     */
    private boolean checkPositionLimit(Signal signal, List<Position> existingPositions) {
        long sameSymbolPositions = existingPositions.stream()
                .filter(p -> p.getSymbol().equals(signal.getSymbol()))
                .count();
        return sameSymbolPositions < config.getMaxPositionsPerSymbol();
    }

    /**
     * 计算仓位大小
     */
    private BigDecimal calculatePositionSize(Signal signal, List<Position> existingPositions) {
        if (signal.getQuantity() != null && signal.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return Decimal.scaleQuantity(signal.getQuantity());
        }
        if (accountInfo == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal accountBalance = resolveSizingBalance();
        if (accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskAmount = accountBalance.multiply(config.getRiskPerTrade());

        BigDecimal entryPrice = signal.getPrice();
        BigDecimal stopLoss = signal.getStopLoss();
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss).abs();
        if (Decimal.isZero(riskPerUnit)) {
            return BigDecimal.ZERO;
        }

        BigDecimal positionSize = riskAmount.divide(riskPerUnit, 3, RoundingMode.DOWN);
        // maxPositionRatio is an account notional ratio; convert notional cap to quantity cap.
        BigDecimal maxPositionNotional = accountBalance.multiply(config.getMaxPositionRatio());
        BigDecimal maxPositionQty = maxPositionNotional.divide(entryPrice, 8, RoundingMode.DOWN);
        positionSize = positionSize.min(maxPositionQty);
        positionSize = Decimal.scaleQuantity(positionSize);

        if (isDonchianBreakout(signal)) {
            positionSize = applyDonchianConstraints(signal, positionSize);
        }

        return positionSize;
    }

    /**
     * Use conservative capital base for sizing:
     * prefer available balance in live trading to avoid oversizing when totalEq is inflated.
     */
    private BigDecimal resolveSizingBalance() {
        BigDecimal total = accountInfo.getTotalBalance();
        BigDecimal available = accountInfo.getAvailableBalance();
        boolean totalValid = total != null && total.compareTo(BigDecimal.ZERO) > 0;
        boolean availableValid = available != null && available.compareTo(BigDecimal.ZERO) > 0;

        if (totalValid && availableValid) {
            return total.min(available);
        }
        if (availableValid) {
            return available;
        }
        return totalValid ? total : BigDecimal.ZERO;
    }

    private boolean isDonchianBreakout(Signal signal) {
        String id = signal.getStrategyId();
        return id != null && id.startsWith("BTC-DONCHIAN48-BREAKOUT");
    }

    private BigDecimal applyDonchianConstraints(Signal signal, BigDecimal qty) {
        ConfigManager cfg = ConfigManager.getInstance();
        BigDecimal qtyStep = cfg.getBigDecimalProperty("btc.donchian.qty.step", new BigDecimal("0.001"));
        BigDecimal minQty = cfg.getBigDecimalProperty("btc.donchian.min.qty", new BigDecimal("0.001"));
        BigDecimal minNotional = cfg.getBigDecimalProperty("btc.donchian.min.notional", new BigDecimal("5"));

        BigDecimal adjusted = applyQtyStep(qty, qtyStep);
        if (adjusted.compareTo(minQty) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal notional = adjusted.multiply(signal.getPrice());
        if (notional.compareTo(minNotional) < 0) {
            return BigDecimal.ZERO;
        }
        return adjusted;
    }

    private BigDecimal applyQtyStep(BigDecimal qty, BigDecimal step) {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return qty;
        }
        BigDecimal steps = qty.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step);
    }

    /**
     * 验证止损
     */
    private boolean validateStopLoss(Signal signal) {
        if (signal.getStopLoss() == null || signal.getStopLoss().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal entryPrice = signal.getPrice();
        BigDecimal stopLoss = signal.getStopLoss();
        BigDecimal distance = entryPrice.subtract(stopLoss).abs()
                .divide(entryPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return distance.compareTo(config.getMaxStopLossPercent()) <= 0;
    }

    private BigDecimal resolveClosableQuantity(Signal signal, List<Position> existingPositions) {
        PositionSide expectedSide = signal.getType() == com.trade.quant.strategy.SignalType.EXIT_LONG
                ? PositionSide.LONG
                : PositionSide.SHORT;
        BigDecimal total = BigDecimal.ZERO;
        for (Position position : existingPositions) {
            if (!position.getSymbol().equals(signal.getSymbol())) {
                continue;
            }
            if (position.getSide() != expectedSide) {
                continue;
            }
            if (position.isClosed()) {
                continue;
            }
            total = total.add(position.getQuantity());
        }
        return Decimal.scaleQuantity(total);
    }

    /**
     * 记录交易结果
     */
    public void recordTradeResult(String strategyIdOrOrderId, boolean isWin, BigDecimal pnl) {
        if (strategyIdOrOrderId == null || strategyIdOrOrderId.isBlank()) {
            return;
        }
        String key = resolveStrategyKey(strategyIdOrOrderId);
        if (isWin) {
            consecutiveLosses.put(key, 0);
        } else {
            int losses = consecutiveLosses.getOrDefault(key, 0) + 1;
            consecutiveLosses.put(key, losses);
        }
    }

    private String resolveStrategyKey(String strategyIdOrOrderId) {
        // Backward compatibility for tests/legacy callers that pass values like "TestStrategy-001".
        if (strategyIdOrOrderId.matches("^[A-Za-z0-9_]+-\\d+$")) {
            int idx = strategyIdOrOrderId.lastIndexOf('-');
            if (idx > 0) {
                return strategyIdOrOrderId.substring(0, idx);
            }
        }
        return strategyIdOrOrderId;
    }

    public void emergencyStop() {
        config.setTradingEnabled(false);
    }

    public void resumeTrading() {
        config.setTradingEnabled(true);
        consecutiveLosses.clear();
        dailyLoss.clear();
    }

    public RiskStatus getRiskStatus() {
        return new RiskStatus(
                config.isTradingEnabled(),
                maxDrawdown,
                consecutiveLosses,
                dailyLoss
        );
    }

    private void logRisk(String reason, Signal signal) {
        logRisk(reason, signal, "");
    }

    private void logRisk(String reason, Signal signal, String detail) {
        String message = String.format("[风控拒绝] %s - 策略: %s, 原因: %s",
                signal.getSymbol(), signal.getStrategyId(), reason);
        if (detail != null && !detail.isBlank()) {
            message = message + ", " + detail;
        }
        System.err.println(message);
    }
}
