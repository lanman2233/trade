package com.trade.quant.execution.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Webhook-based notifier for exchange network/unavailable events.
 */
public class WebhookNetworkAlertNotifier implements NetworkAlertNotifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookNetworkAlertNotifier.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String exchangeName;
    private final String webhookUrl;
    private final long cooldownMillis;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final AtomicLong lastSentAtMillis;

    public WebhookNetworkAlertNotifier(String exchangeName,
                                       String webhookUrl,
                                       int connectTimeoutMs,
                                       int readTimeoutMs,
                                       long cooldownMillis) {
        this.exchangeName = exchangeName == null ? "unknown" : exchangeName;
        this.webhookUrl = webhookUrl;
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Math.max(1000, connectTimeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1000, readTimeoutMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1000, readTimeoutMs), TimeUnit.MILLISECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "network-alert-notifier");
            t.setDaemon(true);
            return t;
        });
        this.lastSentAtMillis = new AtomicLong(0L);
    }

    @Override
    public void notifyExchangeUnavailable(String scene, Throwable error) {
        if (!ExchangeNetworkIssueDetector.isNetworkIssue(error)) {
            return;
        }
        if (!tryAcquireSendWindow(System.currentTimeMillis())) {
            return;
        }
        try {
            executor.execute(() -> send(scene, error));
        } catch (RejectedExecutionException e) {
            logger.warn("Network alert enqueue failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private boolean tryAcquireSendWindow(long nowMillis) {
        while (true) {
            long previous = lastSentAtMillis.get();
            if (cooldownMillis > 0 && nowMillis - previous < cooldownMillis) {
                return false;
            }
            if (lastSentAtMillis.compareAndSet(previous, nowMillis)) {
                return true;
            }
        }
    }

    private void send(String scene, Throwable error) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "exchange_network_unavailable");
            payload.put("exchange", exchangeName);
            payload.put("scene", scene == null ? "unknown" : scene);
            payload.put("message", ExchangeNetworkIssueDetector.summarize(error));
            payload.put("exception", error == null ? "unknown" : error.getClass().getSimpleName());
            payload.put("timestamp", Instant.now().toString());

            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(RequestBody.create(bytes, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Network alert webhook returned non-2xx: code={}", response.code());
                }
            }
        } catch (Exception e) {
            logger.warn("Network alert webhook send failed: {}", e.getMessage());
        }
    }
}

