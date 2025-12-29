package com.trade.quant.exchange;

/**
 * 交易所异常
 */
public class ExchangeException extends Exception {

    private final ErrorCode errorCode;

    public ExchangeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ExchangeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public enum ErrorCode {
        NETWORK_ERROR,           // 网络错误
        API_ERROR,              // API错误
        AUTH_FAILED,            // 认证失败
        INVALID_SYMBOL,         // 无效交易对
        INSUFFICIENT_BALANCE,   // 余额不足
        ORDER_REJECTED,         // 订单被拒绝
        RATE_LIMIT,             // 频率限制
        TIMEOUT,                // 超时
        UNKNOWN                 // 未知错误
    }
}
