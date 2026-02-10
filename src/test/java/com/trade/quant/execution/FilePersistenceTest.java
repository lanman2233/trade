package com.trade.quant.execution;

import com.trade.quant.core.Order;
import com.trade.quant.core.OrderStatus;
import com.trade.quant.core.OrderType;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadPendingOrderRoundTrip() {
        FilePersistence persistence = new FilePersistence(tempDir.toString());
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(Symbol.of("BTC-USDT"))
                .side(Side.BUY)
                .type(OrderType.MARKET)
                .quantity(new BigDecimal("0.010"))
                .price(BigDecimal.ZERO)
                .stopLoss(new BigDecimal("65000"))
                .takeProfit(BigDecimal.ZERO)
                .strategyId("TEST")
                .reduceOnly(false)
                .build();
        order.setStatus(OrderStatus.SUBMITTED);
        order.setExchangeOrderId("12345");
        order.setAvgFillPrice(new BigDecimal("70000"));
        order.setFilledQuantity(new BigDecimal("0.005"));

        persistence.saveOrder(order);

        List<Order> loaded = persistence.loadPendingOrders();
        assertEquals(1, loaded.size());
        Order loadedOrder = loaded.get(0);
        assertEquals(order.getOrderId(), loadedOrder.getOrderId());
        assertEquals(order.getSymbol(), loadedOrder.getSymbol());
        assertEquals(order.getSide(), loadedOrder.getSide());
        assertEquals(order.getType(), loadedOrder.getType());
        assertEquals(OrderStatus.SUBMITTED, loadedOrder.getStatus());
        assertEquals(order.getQuantity(), loadedOrder.getQuantity());
    }

    @Test
    void loadLegacySymbolObjectFormat() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String legacyJson = """
                {
                  "orderId": "%s",
                  "symbol": {"base":"BTC","quote":"USDT"},
                  "side": "SELL",
                  "type": "MARKET",
                  "quantity": 0.02,
                  "price": 0,
                  "status": "PENDING",
                  "stopLoss": 71000.5,
                  "takeProfit": 0,
                  "strategyId": "LEGACY",
                  "reduceOnly": false
                }
                """.formatted(orderId);
        Path legacyFile = tempDir.resolve(orderId + ".json");
        Files.writeString(legacyFile, legacyJson, StandardCharsets.UTF_8);

        FilePersistence persistence = new FilePersistence(tempDir.toString());
        List<Order> loaded = persistence.loadPendingOrders();

        assertEquals(1, loaded.size());
        Order order = loaded.get(0);
        assertNotNull(order);
        assertEquals(Symbol.of("BTC-USDT"), order.getSymbol());
        assertEquals(Side.SELL, order.getSide());
        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertTrue(order.getQuantity().compareTo(BigDecimal.ZERO) > 0);
    }
}
