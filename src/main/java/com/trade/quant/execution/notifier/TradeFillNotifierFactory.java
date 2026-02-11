package com.trade.quant.execution.notifier;

import com.trade.quant.core.ConfigManager;

/**
 * Factory for trade fill notifier.
 */
public final class TradeFillNotifierFactory {

    private TradeFillNotifierFactory() {
    }

    public static TradeFillNotifier fromConfig(ConfigManager cfg, String exchangeName) {
        boolean enabled = cfg.getBooleanProperty("notify.trade.fill.enabled", false);
        String webhookUrl = cfg.getProperty("notify.trade.fill.webhook.url", "").trim();
        if (!enabled || webhookUrl.isEmpty()) {
            return NoopTradeFillNotifier.INSTANCE;
        }
        int connectTimeoutMs = cfg.getIntProperty("notify.trade.fill.connect.timeout.ms", 3000);
        int readTimeoutMs = cfg.getIntProperty("notify.trade.fill.read.timeout.ms", 3000);
        return new WebhookTradeFillNotifier(exchangeName, webhookUrl, connectTimeoutMs, readTimeoutMs);
    }
}

