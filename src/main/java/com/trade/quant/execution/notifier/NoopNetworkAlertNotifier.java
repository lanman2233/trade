package com.trade.quant.execution.notifier;

/**
 * No-op implementation.
 */
public final class NoopNetworkAlertNotifier implements NetworkAlertNotifier {

    public static final NoopNetworkAlertNotifier INSTANCE = new NoopNetworkAlertNotifier();

    private NoopNetworkAlertNotifier() {
    }

    @Override
    public void notifyExchangeUnavailable(String scene, Throwable error) {
        // no-op
    }
}

