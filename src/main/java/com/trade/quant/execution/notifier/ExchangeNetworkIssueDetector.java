package com.trade.quant.execution.notifier;

import com.trade.quant.exchange.ExchangeException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Helper for classifying exchange network/data-unavailable failures.
 */
public final class ExchangeNetworkIssueDetector {

    private ExchangeNetworkIssueDetector() {
    }

    public static boolean isNetworkIssue(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ExchangeException exchangeException) {
                ExchangeException.ErrorCode code = exchangeException.getErrorCode();
                if (code == ExchangeException.ErrorCode.NETWORK_ERROR || code == ExchangeException.ErrorCode.TIMEOUT) {
                    return true;
                }
                if (code == ExchangeException.ErrorCode.API_ERROR && containsNetworkHint(current.getMessage())) {
                    return true;
                }
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            if (current instanceof IOException && containsNetworkHint(current.getMessage())) {
                return true;
            }
            if (containsNetworkHint(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static String summarize(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 3) {
            if (depth > 0) {
                sb.append(" | ");
            }
            String msg = current.getMessage();
            if (msg == null || msg.isBlank()) {
                sb.append(current.getClass().getSimpleName());
            } else {
                sb.append(msg);
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static boolean containsNetworkHint(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("network")
                || m.contains("timeout")
                || m.contains("timed out")
                || m.contains("connection reset")
                || m.contains("connection refused")
                || m.contains("connection aborted")
                || m.contains("broken pipe")
                || m.contains("unknown host")
                || m.contains("no route to host")
                || m.contains("temporarily unavailable")
                || m.contains("service unavailable")
                || m.contains("http 503")
                || m.contains("http 502")
                || m.contains("http 504")
                || m.contains("\"code\":\"50001\"");
    }
}

