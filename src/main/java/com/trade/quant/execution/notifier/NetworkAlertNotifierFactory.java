package com.trade.quant.execution.notifier;

import com.trade.quant.core.ConfigManager;

import java.util.concurrent.TimeUnit;

/**
 * Factory for network alert notifier.
 */
public final class NetworkAlertNotifierFactory {

    private NetworkAlertNotifierFactory() {
    }

    public static NetworkAlertNotifier fromConfig(ConfigManager cfg, String exchangeName) {
        boolean enabled = cfg.getBooleanProperty("notify.exchange.network.enabled", false);
        String webhookUrl = cfg.getProperty("notify.exchange.network.webhook.url", "").trim();
        if (!enabled || webhookUrl.isEmpty()) {
            return NoopNetworkAlertNotifier.INSTANCE;
        }
        int connectTimeoutMs = cfg.getIntProperty("notify.exchange.network.connect.timeout.ms", 3000);
        int readTimeoutMs = cfg.getIntProperty("notify.exchange.network.read.timeout.ms", 3000);
        int cooldownSeconds = cfg.getIntProperty("notify.exchange.network.cooldown.seconds", 60);
        return new WebhookNetworkAlertNotifier(
                exchangeName,
                webhookUrl,
                connectTimeoutMs,
                readTimeoutMs,
                TimeUnit.SECONDS.toMillis(Math.max(0, cooldownSeconds))
        );
    }
}

