package com.trade.quant.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trade.quant.core.Order;
import com.trade.quant.core.OrderStatus;
import com.trade.quant.core.OrderType;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File-based persistence for order recovery.
 */
public class FilePersistence implements Persistence {

    private final String dataDir;
    private final ObjectMapper objectMapper;

    public FilePersistence(String dataDir) {
        this.dataDir = dataDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory: " + dataDir, e);
        }
    }

    @Override
    public void saveOrder(Order order) {
        Path targetPath = getOrderPath(order.getOrderId());
        Path tempPath = Paths.get(targetPath.toString() + ".tmp");
        try {
            objectMapper.writeValue(tempPath.toFile(), serializeOrder(order));
            atomicReplace(tempPath, targetPath);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                // Best effort cleanup.
            }
            throw new RuntimeException("Failed to save order: " + order.getOrderId(), e);
        }
    }

    @Override
    public void updateOrder(Order order) {
        saveOrder(order);
    }

    @Override
    public List<Order> loadPendingOrders() {
        List<Order> orders = new ArrayList<>();

        try {
            File dir = new File(dataDir);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) {
                return orders;
            }
            for (File file : files) {
                try {
                    JsonNode root = objectMapper.readTree(file);
                    Order order = parseOrder(root);
                    if (order.getStatus() == OrderStatus.PENDING
                            || order.getStatus() == OrderStatus.SUBMITTED
                            || order.getStatus() == OrderStatus.PARTIAL) {
                        orders.add(order);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to read order file: " + file.getName() + " - " + e.getMessage());
                    quarantineCorruptedFile(file);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load pending orders: " + e.getMessage());
        }

        return orders;
    }

    @Override
    public void deleteOrder(String orderId) {
        Path path = getOrderPath(orderId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("Failed to delete order: " + orderId);
        }
    }

    private ObjectNode serializeOrder(Order order) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("orderId", safeText(order.getOrderId()));
        if (order.getSymbol() != null) {
            root.put("symbol", order.getSymbol().getBase() + "-" + order.getSymbol().getQuote());
        }
        root.put("side", order.getSide() == null ? "" : order.getSide().name());
        root.put("type", order.getType() == null ? "" : order.getType().name());
        putDecimal(root, "quantity", order.getQuantity());
        putDecimal(root, "price", order.getPrice());
        root.put("status", order.getStatus() == null ? "" : order.getStatus().name());
        putDecimal(root, "stopLoss", order.getStopLoss());
        putDecimal(root, "takeProfit", order.getTakeProfit());
        if (order.getCreateTime() != null) {
            root.put("createTime", order.getCreateTime().toEpochMilli());
        }
        if (order.getFillTime() != null) {
            root.put("fillTime", order.getFillTime().toEpochMilli());
        }
        putDecimal(root, "avgFillPrice", order.getAvgFillPrice());
        putDecimal(root, "filledQuantity", order.getFilledQuantity());
        root.put("clientOrderId", safeText(order.getClientOrderId()));
        root.put("exchangeOrderId", safeText(order.getExchangeOrderId()));
        root.put("strategyId", safeText(order.getStrategyId()));
        root.put("reduceOnly", order.isReduceOnly());
        return root;
    }

    private Order parseOrder(JsonNode root) {
        String orderId = requireText(root, "orderId");
        Symbol symbol = parseSymbol(root.path("symbol"));
        Side side = parseEnum(root, "side", Side.class);
        OrderType type = parseEnum(root, "type", OrderType.class);

        BigDecimal quantity = readDecimal(root, "quantity", BigDecimal.ZERO);
        BigDecimal price = readDecimal(root, "price", BigDecimal.ZERO);
        BigDecimal stopLoss = readDecimal(root, "stopLoss", BigDecimal.ZERO);
        BigDecimal takeProfit = readDecimal(root, "takeProfit", BigDecimal.ZERO);
        boolean reduceOnly = root.path("reduceOnly").asBoolean(false);

        Order order = Order.builder()
                .orderId(orderId)
                .symbol(symbol)
                .side(side)
                .type(type)
                .quantity(quantity)
                .price(price)
                .stopLoss(stopLoss)
                .takeProfit(takeProfit)
                .clientOrderId(readNullableText(root, "clientOrderId"))
                .strategyId(readText(root, "strategyId", ""))
                .reduceOnly(reduceOnly)
                .build();

        OrderStatus status = parseEnumOrDefault(root, "status", OrderStatus.class, OrderStatus.PENDING);
        order.setStatus(status);
        order.setExchangeOrderId(readNullableText(root, "exchangeOrderId"));
        order.setAvgFillPrice(readDecimal(root, "avgFillPrice", BigDecimal.ZERO));
        order.setFilledQuantity(readDecimal(root, "filledQuantity", BigDecimal.ZERO));

        Instant fillTime = readInstant(root, "fillTime");
        if (fillTime != null) {
            order.setFillTime(fillTime);
        }
        return order;
    }

    private Symbol parseSymbol(JsonNode symbolNode) {
        if (symbolNode == null || symbolNode.isNull()) {
            throw new IllegalArgumentException("missing symbol");
        }
        if (symbolNode.isTextual()) {
            return parseSymbolText(symbolNode.asText());
        }
        if (symbolNode.isObject()) {
            String base = readText(symbolNode, "base", "");
            String quote = readText(symbolNode, "quote", "");
            if (!base.isBlank() && !quote.isBlank()) {
                return new Symbol(base, quote);
            }
        }
        throw new IllegalArgumentException("unsupported symbol format");
    }

    private Symbol parseSymbolText(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("empty symbol");
        }
        if (symbol.contains("-") || symbol.contains("_")) {
            return Symbol.of(symbol.replace('_', '-'));
        }
        if (symbol.endsWith("USDT") && symbol.length() > 4) {
            String base = symbol.substring(0, symbol.length() - 4);
            return new Symbol(base, "USDT");
        }
        throw new IllegalArgumentException("invalid symbol: " + symbol);
    }

    private <E extends Enum<E>> E parseEnum(JsonNode root, String field, Class<E> enumType) {
        String value = requireText(root, field).trim().toUpperCase(Locale.ROOT);
        return Enum.valueOf(enumType, value);
    }

    private <E extends Enum<E>> E parseEnumOrDefault(JsonNode root, String field, Class<E> enumType, E defaultValue) {
        String value = readText(root, field, "").trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private BigDecimal readDecimal(JsonNode root, String field, BigDecimal defaultValue) {
        JsonNode valueNode = root.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return defaultValue;
        }
        if (valueNode.isNumber()) {
            return valueNode.decimalValue();
        }
        String raw = valueNode.asText(null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return new BigDecimal(raw);
    }

    private Instant readInstant(JsonNode root, String field) {
        JsonNode valueNode = root.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isNumber()) {
            return Instant.ofEpochMilli(valueNode.asLong());
        }
        String raw = valueNode.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        }
        return Instant.parse(raw);
    }

    private void putDecimal(ObjectNode root, String field, BigDecimal value) {
        if (value == null) {
            root.putNull(field);
            return;
        }
        root.put(field, value.toPlainString());
    }

    private String requireText(JsonNode root, String field) {
        String value = readText(root, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return value;
    }

    private String readText(JsonNode root, String field, String defaultValue) {
        JsonNode valueNode = root.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return defaultValue;
        }
        String value = valueNode.asText();
        return value == null ? defaultValue : value;
    }

    private String readNullableText(JsonNode root, String field) {
        String value = readText(root, field, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path getOrderPath(String orderId) {
        return Paths.get(dataDir, orderId + ".json");
    }

    private void quarantineCorruptedFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        Path source = file.toPath();
        Path target = Paths.get(file.getAbsolutePath() + ".corrupt");
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("Corrupted order file moved to: " + target.getFileName());
        } catch (IOException ignored) {
            // Keep original file when quarantine fails to avoid data loss.
        }
    }
}

