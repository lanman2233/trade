package com.trade.quant.execution;

import com.trade.quant.core.Order;

import java.util.List;

/**
 * 订单持久化接口
 * 用于异常恢复
 */
public interface Persistence {

    /**
     * 保存订单
     */
    void saveOrder(Order order);

    /**
     * 更新订单
     */
    void updateOrder(Order order);

    /**
     * 加载待处理订单
     */
    List<Order> loadPendingOrders();

    /**
     * 删除订单
     */
    void deleteOrder(String orderId);
}
