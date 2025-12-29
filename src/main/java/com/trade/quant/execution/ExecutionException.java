package com.trade.quant.execution;

/**
 * 执行异常
 */
public class ExecutionException extends Exception {

    public ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
