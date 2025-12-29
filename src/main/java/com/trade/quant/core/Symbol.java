package com.trade.quant.core;

import java.util.Objects;

/**
 * 交易对
 * 严格遵守：仅支持 USDT 本位合约
 */
public class Symbol {
    private final String base;      // 基础货币，如 BTC
    private final String quote;     // 报价货币，仅支持 USDT

    public Symbol(String base, String quote) {
        if (!"USDT".equals(quote)) {
            throw new IllegalArgumentException("仅支持 USDT 本位合约，当前报价货币: " + quote);
        }
        this.base = base.toUpperCase();
        this.quote = quote.toUpperCase();
    }

    public static Symbol of(String symbol) {
        String[] parts = symbol.split("[-_]");
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的交易对格式: " + symbol);
        }
        return new Symbol(parts[0], parts[1]);
    }

    public String getBase() {
        return base;
    }

    public String getQuote() {
        return quote;
    }

    public String toPairString() {
        return base + quote;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Symbol symbol = (Symbol) o;
        return Objects.equals(base, symbol.base) && Objects.equals(quote, symbol.quote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, quote);
    }

    @Override
    public String toString() {
        return base + quote;
    }
}
