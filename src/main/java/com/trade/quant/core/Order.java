package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单（系统内部交易意图）
 */
public class Order {
    private final String orderId;
    private final Symbol symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal quantity;
    private BigDecimal price;
    private OrderStatus status;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final Instant createTime;
    private Instant fillTime;
    private BigDecimal avgFillPrice;
    private BigDecimal filledQuantity;
    private final String clientOrderId;
    private String exchangeOrderId;
    private final String strategyId;
    private final boolean reduceOnly;

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.symbol = builder.symbol;
        this.side = builder.side;
        this.type = builder.type;
        this.quantity = builder.quantity;
        this.price = builder.price;
        this.status = OrderStatus.PENDING;
        this.stopLoss = builder.stopLoss;
        this.takeProfit = builder.takeProfit;
        this.createTime = Instant.now();
        this.clientOrderId = builder.clientOrderId;
        this.exchangeOrderId = null;
        this.strategyId = builder.strategyId;
        this.reduceOnly = builder.reduceOnly;
        this.filledQuantity = BigDecimal.ZERO;
    }

    public String getOrderId() { return orderId; }
    public Symbol getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public BigDecimal getTakeProfit() { return takeProfit; }
    public Instant getCreateTime() { return createTime; }
    public Instant getFillTime() { return fillTime; }
    public void setFillTime(Instant fillTime) { this.fillTime = fillTime; }
    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public void setAvgFillPrice(BigDecimal avgFillPrice) { this.avgFillPrice = avgFillPrice; }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(BigDecimal filledQuantity) { this.filledQuantity = filledQuantity; }
    public String getClientOrderId() { return clientOrderId; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }
    public String getStrategyId() { return strategyId; }
    public boolean isReduceOnly() { return reduceOnly; }

    public BigDecimal getValue() {
        BigDecimal effectivePrice = (type == OrderType.MARKET)
                ? avgFillPrice != null ? avgFillPrice : BigDecimal.ZERO
                : price != null ? price : BigDecimal.ZERO;
        return effectivePrice.multiply(quantity);
    }

    public boolean isLimitOrder() {
        return type == OrderType.LIMIT;
    }

    public boolean isMarketOrder() {
        return type == OrderType.MARKET;
    }

    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    public boolean isPartiallyFilled() {
        return status == OrderStatus.PARTIAL;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String orderId;
        private Symbol symbol;
        private Side side;
        private OrderType type;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal stopLoss;
        private BigDecimal takeProfit;
        private String clientOrderId;
        private String strategyId;
        private boolean reduceOnly;

        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder symbol(Symbol symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder side(Side side) {
            this.side = side;
            return this;
        }

        public Builder type(OrderType type) {
            this.type = type;
            return this;
        }

        public Builder quantity(BigDecimal quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder stopLoss(BigDecimal stopLoss) {
            this.stopLoss = stopLoss;
            return this;
        }

        public Builder takeProfit(BigDecimal takeProfit) {
            this.takeProfit = takeProfit;
            return this;
        }

        public Builder clientOrderId(String clientOrderId) {
            this.clientOrderId = clientOrderId;
            return this;
        }

        public Builder strategyId(String strategyId) {
            this.strategyId = strategyId;
            return this;
        }

        public Builder reduceOnly(boolean reduceOnly) {
            this.reduceOnly = reduceOnly;
            return this;
        }

        public Order build() {
            if (symbol == null || side == null || type == null) {
                throw new IllegalStateException("symbol, side, type must be set");
            }
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("quantity must be positive");
            }
            if (type == OrderType.LIMIT && price == null) {
                throw new IllegalStateException("limit order must set price");
            }
            if (stopLoss == null) {
                throw new IllegalStateException("stopLoss must be set");
            }
            return new Order(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Order{id=%s, symbol=%s, side=%s, type=%s, qty=%s, price=%s, stopLoss=%s, reduceOnly=%s, status=%s}",
                orderId, symbol, side, type, quantity, price, stopLoss, reduceOnly, status);
    }
}
