package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单
 * 系统内交易意图的表示，非交易所订单
 */
public class Order {
    private final String orderId;          // 订单ID（UUID）
    private final Symbol symbol;            // 交易对
    private final Side side;                // 方向
    private final OrderType type;           // 订单类型
    private final BigDecimal quantity;      // 数量（张）
    private BigDecimal price;               // 价格（仅限价单）
    private OrderStatus status;             // 状态
    private final BigDecimal stopLoss;      // 止损价格（必填）
    private final BigDecimal takeProfit;    // 止盈价格（可选）
    private final Instant createTime;       // 创建时间
    private Instant fillTime;               // 成交时间
    private BigDecimal avgFillPrice;        // 平均成交价
    private BigDecimal filledQuantity;      // 已成交数量
    private final String clientOrderId;     // 客户端订单ID
    private String exchangeOrderId;         // 交易所订单ID
    private final String strategyId;        // 所属策略ID

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

    /**
     * 计算订单价值
     */
    public BigDecimal getValue() {
        BigDecimal effectivePrice = (type == OrderType.MARKET)
            ? avgFillPrice != null ? avgFillPrice : BigDecimal.ZERO
            : price != null ? price : BigDecimal.ZERO;
        return effectivePrice.multiply(quantity);
    }

    /**
     * 是否为限价单
     */
    public boolean isLimitOrder() {
        return type == OrderType.LIMIT;
    }

    /**
     * 是否为市价单
     */
    public boolean isMarketOrder() {
        return type == OrderType.MARKET;
    }

    /**
     * 是否已成交
     */
    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    /**
     * 是否部分成交
     */
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

        public Order build() {
            if (symbol == null || side == null || type == null) {
                throw new IllegalStateException("symbol, side, type 必须设置");
            }
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("quantity 必须为正数");
            }
            if (type == OrderType.LIMIT && price == null) {
                throw new IllegalStateException("限价单必须设置价格");
            }
            if (stopLoss == null) {
                throw new IllegalStateException("止损价格必须设置");
            }
            return new Order(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Order{id=%s, symbol=%s, side=%s, type=%s, qty=%s, price=%s, stopLoss=%s, status=%s}",
                orderId, symbol, side, type, quantity, price, stopLoss, status);
    }
}
