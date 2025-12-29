package com.trade.quant.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trade.quant.core.Order;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件持久化实现
 */
public class FilePersistence implements Persistence {

    private final String dataDir;
    private final ObjectMapper objectMapper;

    public FilePersistence(String dataDir) {
        this.dataDir = dataDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保目录存在
        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            throw new RuntimeException("无法创建数据目录: " + dataDir, e);
        }
    }

    @Override
    public void saveOrder(Order order) {
        Path path = getOrderPath(order.getOrderId());
        try {
            objectMapper.writeValue(path.toFile(), order);
        } catch (IOException e) {
            throw new RuntimeException("保存订单失败", e);
        }
    }

    @Override
    public void updateOrder(Order order) {
        saveOrder(order); // 覆盖保存
    }

    @Override
    public List<Order> loadPendingOrders() {
        List<Order> orders = new ArrayList<>();

        try {
            File dir = new File(dataDir);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

            if (files != null) {
                for (File file : files) {
                    try {
                        Order order = objectMapper.readValue(file, Order.class);
                        if (order.getStatus() == com.trade.quant.core.OrderStatus.PENDING ||
                            order.getStatus() == com.trade.quant.core.OrderStatus.SUBMITTED ||
                            order.getStatus() == com.trade.quant.core.OrderStatus.PARTIAL) {
                            orders.add(order);
                        }
                    } catch (IOException e) {
                        System.err.println("读取订单失败: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("加载订单失败: " + e.getMessage());
        }

        return orders;
    }

    @Override
    public void deleteOrder(String orderId) {
        Path path = getOrderPath(orderId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("删除订单失败: " + orderId);
        }
    }

    private Path getOrderPath(String orderId) {
        return Paths.get(dataDir, orderId + ".json");
    }
}
