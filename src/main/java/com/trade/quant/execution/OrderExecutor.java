package com.trade.quant.execution;

import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单执行器
 * 1. 幂等下单
 * 2. 订单状态跟踪
 * 3. 异常恢复
 * 4. 执行结果通知
 */
public class OrderExecutor {

    private static final Logger logger = LoggerFactory.getLogger(OrderExecutor.class);

    private final Exchange exchange;
    private final Map<String, Order> pendingOrders;      // 待处理订单
    private final Map<String, Order> submittedOrders;    // 已提交订单
    private final Map<String, Order> filledOrders;       // 已成交订单
    private final List<OrderListener> listeners;
    private final Persistence persistence;

    public OrderExecutor(Exchange exchange, Persistence persistence) {
        this.exchange = exchange;
        this.persistence = persistence;
        this.pendingOrders = new ConcurrentHashMap<>();
        this.submittedOrders = new ConcurrentHashMap<>();
        this.filledOrders = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();

        // 恢复未完成的订单
        recoverOrders();
    }

    /**
     * 提交订单（幂等）
     */
    public String submitOrder(Order order) throws ExecutionException {
        if (pendingOrders.containsKey(order.getOrderId()) ||
            submittedOrders.containsKey(order.getOrderId())) {
            logger.warn("订单已存在，跳过: {}", order.getOrderId());
            return order.getOrderId();
        }

        pendingOrders.put(order.getOrderId(), order);
        persistence.saveOrder(order);

        try {
            String exchangeOrderId = exchange.placeOrder(order);

            order.setStatus(OrderStatus.SUBMITTED);
            order.setExchangeOrderId(exchangeOrderId);

            pendingOrders.remove(order.getOrderId());
            submittedOrders.put(order.getOrderId(), order);
            persistence.updateOrder(order);

            logger.info("订单已提交: {} -> {}", order.getOrderId(), exchangeOrderId);
            notifyListeners(OrderListener::onOrderSubmitted, order);

            return order.getOrderId();

        } catch (ExchangeException e) {
            order.setStatus(OrderStatus.REJECTED);
            pendingOrders.remove(order.getOrderId());
            persistence.updateOrder(order);

            logger.error("订单提交失败: {} - {}", order.getOrderId(), e.getMessage());
            notifyListeners(l -> l.onOrderFailed(order, e.getMessage()));
            throw new ExecutionException("订单提交失败", e);
        }
    }

    /**
     * 取消订单（幂等）
     */
    public boolean cancelOrder(String orderId) throws ExecutionException {
        Order order = submittedOrders.get(orderId);
        if (order == null) {
            logger.warn("订单不存在或已完成: {}", orderId);
            return false;
        }

        try {
            String exchangeOrderId = order.getExchangeOrderId() != null ? order.getExchangeOrderId() : orderId;
            boolean success = exchange.cancelOrder(exchangeOrderId, order.getSymbol());

            if (success) {
                order.setStatus(OrderStatus.CANCELLED);
                submittedOrders.remove(orderId);
                persistence.updateOrder(order);

                logger.info("订单已取消: {}", orderId);
                notifyListeners(OrderListener::onOrderCancelled, order);
            }

            return success;

        } catch (ExchangeException e) {
            logger.error("取消订单失败: {} - {}", orderId, e.getMessage());
            throw new ExecutionException("取消订单失败", e);
        }
    }

    /**
     * 查询订单状态
     */
    public void checkOrderStatus(String orderId) {
        Order order = submittedOrders.get(orderId);
        if (order == null) {
            return;
        }

        try {
            String exchangeOrderId = order.getExchangeOrderId() != null ? order.getExchangeOrderId() : orderId;
            Order updatedOrder = exchange.getOrder(exchangeOrderId, order.getSymbol());

            if (updatedOrder.getStatus() == OrderStatus.FILLED) {
                order.setStatus(OrderStatus.FILLED);
                order.setFilledQuantity(updatedOrder.getFilledQuantity());
                order.setAvgFillPrice(updatedOrder.getAvgFillPrice());

                submittedOrders.remove(orderId);
                filledOrders.put(orderId, order);
                persistence.updateOrder(order);

                logger.info("订单已成交: {} @ {}", orderId, order.getAvgFillPrice());
                notifyListeners(OrderListener::onOrderFilled, order);

            } else if (updatedOrder.getStatus() == OrderStatus.PARTIAL) {
                order.setStatus(OrderStatus.PARTIAL);
                order.setFilledQuantity(updatedOrder.getFilledQuantity());
                order.setAvgFillPrice(updatedOrder.getAvgFillPrice());
                persistence.updateOrder(order);

                logger.info("订单部分成交: {}/{}",
                        order.getFilledQuantity(), order.getQuantity());
                notifyListeners(OrderListener::onOrderPartial, order);

            } else if (updatedOrder.getStatus() == OrderStatus.CANCELLED) {
                order.setStatus(OrderStatus.CANCELLED);
                submittedOrders.remove(orderId);
                persistence.updateOrder(order);

                logger.info("订单已取消: {}", orderId);
                notifyListeners(OrderListener::onOrderCancelled, order);
            }

        } catch (ExchangeException e) {
            logger.error("查询订单状态失败: {} - {}", orderId, e.getMessage());
        }
    }

    /**
     * 恢复未完成的订单
     */
    private void recoverOrders() {
        List<Order> orders = persistence.loadPendingOrders();

        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.PENDING) {
                pendingOrders.put(order.getOrderId(), order);
            } else if (order.getStatus() == OrderStatus.SUBMITTED ||
                     order.getStatus() == OrderStatus.PARTIAL) {
                submittedOrders.put(order.getOrderId(), order);
                checkOrderStatus(order.getOrderId());
            }
        }

        logger.info("恢复 {} 个待处理订单", pendingOrders.size() + submittedOrders.size());
    }

    /**
     * 添加监听器
     */
    public void addListener(OrderListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
     */
    public void removeListener(OrderListener listener) {
        listeners.remove(listener);
    }

    /**
     * 通知监听器
     */
    private void notifyListener(OrderListener listener, Order order) {
        try {
            listener.onOrderUpdated(order);
        } catch (Exception e) {
            logger.error("监听器回调失败: {}", e.getMessage());
        }
    }

    private void notifyListeners(java.util.function.BiConsumer<OrderListener, Order> action, Order order) {
        for (OrderListener listener : listeners) {
            try {
                action.accept(listener, order);
            } catch (Exception e) {
                logger.error("监听器回调失败: {}", e.getMessage());
            }
        }
    }

    private void notifyListeners(java.util.function.Consumer<OrderListener> action) {
        for (OrderListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                logger.error("监听器回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取订单统计
     */
    public OrderStatistics getStatistics() {
        return new OrderStatistics(
                pendingOrders.size(),
                submittedOrders.size(),
                filledOrders.size()
        );
    }

    /**
     * 订单监听器
     */
    public interface OrderListener {
        default void onOrderSubmitted(Order order) {}
        default void onOrderFilled(Order order) {}
        default void onOrderPartial(Order order) {}
        default void onOrderCancelled(Order order) {}
        default void onOrderFailed(Order order, String reason) {}
        default void onOrderUpdated(Order order) {}
    }

    /**
     * 订单统计
     */
    public record OrderStatistics(int pending, int submitted, int filled) {}
}
