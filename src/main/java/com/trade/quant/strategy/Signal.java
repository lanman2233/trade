package com.trade.quant.strategy;

import com.trade.quant.core.*;

import java.math.BigDecimal;

/**
 * 交易信号
 * 策略层的输出，表示交易意图
 */
public class Signal {

    private final String strategyId;
    private final Symbol symbol;
    private final SignalType type;
    private final Side side;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final String reason;
    private final TradeMetrics metrics;
    private final ExitReason exitReason;
    private final boolean maker;
    private final long timestamp;

    public Signal(String strategyId, Symbol symbol, SignalType type, Side side,
                 BigDecimal price, BigDecimal quantity, BigDecimal stopLoss,
                 BigDecimal takeProfit, String reason) {
        this(strategyId, symbol, type, side, price, quantity, stopLoss, takeProfit, reason, null, null);
    }

    public Signal(String strategyId, Symbol symbol, SignalType type, Side side,
                 BigDecimal price, BigDecimal quantity, BigDecimal stopLoss,
                 BigDecimal takeProfit, String reason,
                 TradeMetrics metrics, ExitReason exitReason) {
        this(strategyId, symbol, type, side, price, quantity, stopLoss, takeProfit, reason, metrics, exitReason, false);
    }

    public Signal(String strategyId, Symbol symbol, SignalType type, Side side,
                 BigDecimal price, BigDecimal quantity, BigDecimal stopLoss,
                 BigDecimal takeProfit, String reason,
                 TradeMetrics metrics, ExitReason exitReason, boolean maker) {
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.type = type;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.reason = reason;
        this.metrics = metrics;
        this.exitReason = exitReason;
        this.maker = maker;
        this.timestamp = System.currentTimeMillis();
    }

    public String getStrategyId() { return strategyId; }
    public Symbol getSymbol() { return symbol; }
    public SignalType getType() { return type; }
    public Side getSide() { return side; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public BigDecimal getTakeProfit() { return takeProfit; }
    public String getReason() { return reason; }
    public TradeMetrics getMetrics() { return metrics; }
    public ExitReason getExitReason() { return exitReason; }
    public boolean isMaker() { return maker; }
    public long getTimestamp() { return timestamp; }

    /**
     * 是否为入场信号
     */
    public boolean isEntry() {
        return type == SignalType.ENTRY_LONG || type == SignalType.ENTRY_SHORT;
    }

    /**
     * 是否为出场信号
     */
    public boolean isExit() {
        return type == SignalType.EXIT_LONG || type == SignalType.EXIT_SHORT;
    }

    /**
     * 转换为订单
     */
    public Order toOrder(OrderType orderType) {
        return Order.builder()
                .orderId(java.util.UUID.randomUUID().toString())
                .symbol(symbol)
                .side(side)
                .type(orderType)
                .quantity(quantity)
                .price(price)
                .stopLoss(stopLoss)
                .takeProfit(takeProfit)
                .strategyId(strategyId)
                .build();
    }

    @Override
    public String toString() {
        return String.format("Signal{type=%s, symbol=%s, side=%s, price=%s, qty=%s, stopLoss=%s, reason=%s}",
                type, symbol, side, price, quantity, stopLoss, reason);
    }
}
