package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成交记录
 */
public class Trade {
    private final String tradeId;           // 成交ID
    private final String orderId;           // 订单ID
    private final Symbol symbol;            // 交易对
    private final Side side;                // 方向
    private final BigDecimal price;         // 成交价格
    private final BigDecimal quantity;      // 成交数量
    private final BigDecimal fee;           // 手续费
    private final Instant timestamp;        // 成交时间
    private final String strategyId;        // 策略ID

    public Trade(String tradeId, String orderId, Symbol symbol, Side side,
                BigDecimal price, BigDecimal quantity, BigDecimal fee, String strategyId) {
        this.tradeId = tradeId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.fee = fee;
        this.strategyId = strategyId;
        this.timestamp = Instant.now();
    }

    public String getTradeId() { return tradeId; }
    public String getOrderId() { return orderId; }
    public Symbol getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getFee() { return fee; }
    public Instant getTimestamp() { return timestamp; }
    public String getStrategyId() { return strategyId; }

    /**
     * 计算成交价值
     */
    public BigDecimal getValue() {
        return price.multiply(quantity);
    }

    /**
     * 计算净手续费率
     */
    public BigDecimal getFeeRate() {
        if (getValue().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return Decimal.divide(fee, getValue()).multiply(BigDecimal.valueOf(100));
    }

    @Override
    public String toString() {
        return String.format("Trade{id=%s, symbol=%s, side=%s, price=%s, qty=%s, fee=%s, time=%s}",
                tradeId, symbol, side, price, quantity, fee, timestamp);
    }
}
