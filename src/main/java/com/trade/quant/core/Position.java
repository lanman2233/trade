package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持仓
 * 系统内持仓状态的表示
 */
public class Position {
    private final Symbol symbol;
    private final PositionSide side;
    private BigDecimal entryPrice;      // 开仓均价
    private BigDecimal quantity;        // 持仓数量（张）
    private BigDecimal unrealizedPnl;   // 未实现盈亏
    private BigDecimal realizedPnl;     // 已实现盈亏
    private final BigDecimal stopLoss;  // 止损价格
    private final Instant openTime;     // 开仓时间
    private BigDecimal leverage;        // 杠杆倍数

    public Position(Symbol symbol, PositionSide side, BigDecimal entryPrice,
                   BigDecimal quantity, BigDecimal stopLoss, BigDecimal leverage) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.stopLoss = stopLoss;
        this.leverage = leverage;
        this.openTime = Instant.now();
        this.unrealizedPnl = BigDecimal.ZERO;
        this.realizedPnl = BigDecimal.ZERO;
    }

    public Symbol getSymbol() { return symbol; }
    public PositionSide getSide() { return side; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public Instant getOpenTime() { return openTime; }
    public BigDecimal getLeverage() { return leverage; }

    /**
     * 更新未实现盈亏
     * @param markPrice 标记价格
     */
    public void updateUnrealizedPnl(BigDecimal markPrice) {
        BigDecimal priceDiff;
        if (side == PositionSide.LONG) {
            priceDiff = markPrice.subtract(entryPrice);
        } else {
            priceDiff = entryPrice.subtract(markPrice);
        }
        this.unrealizedPnl = priceDiff.multiply(quantity);
    }

    /**
     * 检查是否触发止损
     */
    public boolean isStopLossTriggered(BigDecimal markPrice) {
        if (stopLoss == null) return false;

        if (side == PositionSide.LONG) {
            return markPrice.compareTo(stopLoss) <= 0;
        } else {
            return markPrice.compareTo(stopLoss) >= 0;
        }
    }

    /**
     * 计算持仓价值
     */
    public BigDecimal getValue() {
        return entryPrice.multiply(quantity);
    }

    /**
     * 计算持仓收益率
     */
    public BigDecimal getReturnRate() {
        if (entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return Decimal.divide(unrealizedPnl, getValue())
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 减仓
     */
    public void reduce(BigDecimal quantityToReduce) {
        if (quantityToReduce.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("减仓数量不能超过当前持仓");
        }
        this.quantity = quantity.subtract(quantityToReduce);
    }

    /**
     * 是否已平仓
     */
    public boolean isClosed() {
        return Decimal.isZero(quantity);
    }

    @Override
    public String toString() {
        return String.format("Position{symbol=%s, side=%s, entryPrice=%s, qty=%s, unrealizedPnl=%s, stopLoss=%s}",
                symbol, side, entryPrice, quantity, unrealizedPnl, stopLoss);
    }
}
