package com.trade.quant.exchange;

/**
 * 交易所工厂
 */
public final class ExchangeFactory {

    private ExchangeFactory() {}

    /**
     * 创建交易所实例
     * @param exchangeName 交易所名称（binance、okx）
     */
    public static Exchange createExchange(String exchangeName) {
        if ("binance".equalsIgnoreCase(exchangeName)) {
            return new BinanceExchange();
        } else if ("okx".equalsIgnoreCase(exchangeName)) {
            throw new UnsupportedOperationException("OKX 交易所暂未实现");
        } else {
            throw new IllegalArgumentException("不支持的交易所: " + exchangeName);
        }
    }

    /**
     * 创建Binance交易所实例
     */
    public static Exchange createBinance() {
        return new BinanceExchange();
    }
}
