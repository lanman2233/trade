package com.trade.quant.execution.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Webhook implementation for confirmed trade fills.
 * Only push after real fill quantity is available.
 */
public class WebhookTradeFillNotifier implements TradeFillNotifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookTradeFillNotifier.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long DEDUPE_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final String exchangeName;
    private final String webhookUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final ConcurrentMap<String, Long> sentEventIds;

    public WebhookTradeFillNotifier(String exchangeName,
                                    String webhookUrl,
                                    int connectTimeoutMs,
                                    int readTimeoutMs) {
        this.exchangeName = exchangeName == null ? "unknown" : exchangeName;
        this.webhookUrl = webhookUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Math.max(1000, connectTimeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1000, readTimeoutMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1000, readTimeoutMs), TimeUnit.MILLISECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trade-fill-notifier");
            t.setDaemon(true);
            return t;
        });
        this.sentEventIds = new ConcurrentHashMap<>();
    }

    @Override
    public void notifyEntryFilled(String fillEventId,
                                  String strategyId,
                                  Symbol symbol,
                                  Side side,
                                  BigDecimal avgFillPrice,
                                  BigDecimal filledQuantity) {
        if (!hasValidFill(avgFillPrice, filledQuantity)) {
            return;
        }
        if (!markEvent(fillEventId)) {
            return;
        }
        Map<String, Object> payload = basePayload("trade_fill_entry", fillEventId, strategyId, symbol, side);
        payload.put("avgFillPrice", avgFillPrice);
        payload.put("filledQuantity", filledQuantity);
        sendAsync(payload);
    }

    @Override
    public void notifyExitFilled(String fillEventId,
                                 String strategyId,
                                 Symbol symbol,
                                 Side side,
                                 BigDecimal avgFillPrice,
                                 BigDecimal filledQuantity,
                                 BigDecimal pnl) {
        if (!hasValidFill(avgFillPrice, filledQuantity)) {
            return;
        }
        if (!markEvent(fillEventId)) {
            return;
        }
        Map<String, Object> payload = basePayload("trade_fill_exit", fillEventId, strategyId, symbol, side);
        payload.put("avgFillPrice", avgFillPrice);
        payload.put("filledQuantity", filledQuantity);
        payload.put("pnl", pnl == null ? BigDecimal.ZERO : pnl);
        sendAsync(payload);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private boolean hasValidFill(BigDecimal avgFillPrice, BigDecimal filledQuantity) {
        return avgFillPrice != null && avgFillPrice.compareTo(BigDecimal.ZERO) > 0
                && filledQuantity != null && filledQuantity.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean markEvent(String fillEventId) {
        if (fillEventId == null || fillEventId.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = sentEventIds.putIfAbsent(fillEventId, now);
        cleanupDedupeCache(now);
        return previous == null;
    }

    private void cleanupDedupeCache(long now) {
        if (sentEventIds.size() < 2048) {
            return;
        }
        for (Map.Entry<String, Long> entry : sentEventIds.entrySet()) {
            Long sentAt = entry.getValue();
            if (sentAt == null || now - sentAt > DEDUPE_TTL_MILLIS) {
                sentEventIds.remove(entry.getKey(), sentAt);
            }
        }
    }

    private Map<String, Object> basePayload(String event,
                                            String fillEventId,
                                            String strategyId,
                                            Symbol symbol,
                                            Side side) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("exchange", exchangeName);
        payload.put("fillEventId", fillEventId == null ? "" : fillEventId);
        payload.put("strategyId", strategyId == null ? "" : strategyId);
        payload.put("symbol", symbol == null ? "" : symbol.toPairString());
        payload.put("side", side == null ? "" : side.name());
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    private void sendAsync(Map<String, Object> payload) {
        try {
            executor.execute(() -> send(payload));
        } catch (RejectedExecutionException e) {
            logger.warn("Trade fill webhook enqueue failed: {}", e.getMessage());
        }
    }

    private void send(Map<String, Object> payload) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(RequestBody.create(bytes, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Trade fill webhook returned non-2xx: code={}", response.code());
                }
            }
        } catch (Exception e) {
            logger.warn("Trade fill webhook send failed: {}", e.getMessage());
        }
    }
}

