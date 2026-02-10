package com.trade.quant.exchange;

import com.trade.quant.core.Side;
import com.trade.quant.core.Symbol;

import java.math.BigDecimal;

/**
 * Capability interface for exchange-hosted protective stop orders.
 * Used to unify Binance / OKX stop placement and cancellation behavior.
 */
public interface ProtectiveStopCapableExchange {

    /**
     * Normalize market quantity according to exchange symbol rules.
     */
    BigDecimal normalizeMarketQuantity(Symbol symbol, BigDecimal rawQuantity) throws ExchangeException;

    /**
     * Normalize stop trigger price according to exchange symbol rules.
     */
    BigDecimal normalizeStopPrice(Symbol symbol, Side closeSide, BigDecimal rawStopPrice) throws ExchangeException;

    /**
     * Place reduce-only exchange-hosted stop-market order.
     * @return exchange order id (or algo order id).
     */
    String placeReduceOnlyStopMarketOrder(Symbol symbol,
                                          Side side,
                                          BigDecimal stopPrice,
                                          BigDecimal quantity,
                                          String clientOrderId) throws ExchangeException;

    /**
     * Cancel stale reduce-only protective stop orders for symbol.
     * @return number of successfully canceled orders.
     */
    int cancelReduceOnlyStopOrders(Symbol symbol) throws ExchangeException;
}
