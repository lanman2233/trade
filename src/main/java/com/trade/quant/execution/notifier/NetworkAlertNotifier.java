package com.trade.quant.execution.notifier;

/**
 * Notification interface for exchange network/data-unavailable alerts.
 */
public interface NetworkAlertNotifier {

    /**
     * Notify when exchange data cannot be fetched due to network/unavailable issues.
     */
    void notifyExchangeUnavailable(String scene, Throwable error);

    /**
     * Release notifier resources.
     */
    default void close() {
    }
}

