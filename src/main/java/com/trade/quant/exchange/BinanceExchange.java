package com.trade.quant.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.quant.core.*;
import com.trade.quant.market.MarketDataListener;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Binance USDT-M futures exchange implementation.
 */
public class BinanceExchange implements Exchange, ProtectiveStopCapableExchange {

    private static final String PROD_REST_BASE_URL = "https://fapi.binance.com";
    private static final String PROD_WS_BASE_URL = "wss://fstream.binance.com/ws";
    private static final String TESTNET_REST_BASE_URL = "https://demo-fapi.binance.com";
    private static final String TESTNET_WS_BASE_URL = "wss://fstream.binancefuture.com/ws";
    private static final long SYMBOL_RULE_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long WS_RECONNECT_BASE_DELAY_MS = 1000L;
    private static final long WS_RECONNECT_MAX_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long REST_FALLBACK_POLL_MS = 2000L;

    private String apiKey;
    private String secretKey;
    private String restBaseUrl;
    private String wsBaseUrl;
    private volatile boolean testnetEnabled;
    private OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, WebSocket> webSockets;
    private final Map<String, SymbolRules> symbolRulesCache;
    private final Map<String, StreamSubscription> streamSubscriptions;
    private final Map<String, ScheduledFuture<?>> reconnectTasks;
    private final Map<String, ScheduledFuture<?>> fallbackPollTasks;
    private final ScheduledExecutorService wsExecutor;
    private boolean connected = false;
    private volatile boolean shuttingDown = false;

    public BinanceExchange() {
        this.restBaseUrl = PROD_REST_BASE_URL;
        this.wsBaseUrl = PROD_WS_BASE_URL;
        this.testnetEnabled = false;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.webSockets = new ConcurrentHashMap<>();
        this.symbolRulesCache = new ConcurrentHashMap<>();
        this.streamSubscriptions = new ConcurrentHashMap<>();
        this.reconnectTasks = new ConcurrentHashMap<>();
        this.fallbackPollTasks = new ConcurrentHashMap<>();
        this.wsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "binance-ws-supervisor");
            t.setDaemon(true);
            return t;
        });
    }

    public void configureTestnet(boolean enabled, String restBaseUrlOverride, String wsBaseUrlOverride) {
        this.testnetEnabled = enabled;
        if (enabled) {
            this.restBaseUrl = normalizeBaseUrl(firstNonBlank(restBaseUrlOverride, TESTNET_REST_BASE_URL));
            this.wsBaseUrl = normalizeWsBaseUrl(firstNonBlank(wsBaseUrlOverride, TESTNET_WS_BASE_URL));
        } else {
            this.restBaseUrl = normalizeBaseUrl(firstNonBlank(restBaseUrlOverride, PROD_REST_BASE_URL));
            this.wsBaseUrl = normalizeWsBaseUrl(firstNonBlank(wsBaseUrlOverride, PROD_WS_BASE_URL));
        }
    }

    public boolean isTestnetEnabled() {
        return testnetEnabled;
    }

    private static final class SymbolRules {
        private final BigDecimal tickSize;
        private final BigDecimal lotStepSize;
        private final BigDecimal marketStepSize;
        private final BigDecimal lotMinQty;
        private final BigDecimal marketMinQty;
        private final BigDecimal minNotional;
        private final long loadedAtMillis;

        private SymbolRules(BigDecimal tickSize, BigDecimal lotStepSize, BigDecimal marketStepSize,
                            BigDecimal lotMinQty, BigDecimal marketMinQty, BigDecimal minNotional,
                            long loadedAtMillis) {
            this.tickSize = tickSize;
            this.lotStepSize = lotStepSize;
            this.marketStepSize = marketStepSize;
            this.lotMinQty = lotMinQty;
            this.marketMinQty = marketMinQty;
            this.minNotional = minNotional;
            this.loadedAtMillis = loadedAtMillis;
        }
    }

    private static final class StreamSubscription {
        private final String stream;
        private final Symbol symbol;
        private final Interval interval;
        private final MarketDataListener listener;
        private final boolean tickerStream;
        private int reconnectAttempts;

        private StreamSubscription(String stream, Symbol symbol, Interval interval,
                                   MarketDataListener listener, boolean tickerStream) {
            this.stream = stream;
            this.symbol = symbol;
            this.interval = interval;
            this.listener = listener;
            this.tickerStream = tickerStream;
            this.reconnectAttempts = 0;
        }
    }

    @Override
    public String getName() {
        return testnetEnabled ? "Binance-Testnet" : "Binance";
    }

    @Override
    public void setApiKey(String apiKey, String secretKey, String passphrase) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    @Override
    public void setProxy(String host, int port) {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        this.httpClient = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void setLeverage(Symbol symbol, int leverage) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/leverage";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            params.put("leverage", String.valueOf(leverage));
            signedRequest(endpoint, "POST", params);
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Set leverage failed", e);
        }
    }

    public void setMarginType(Symbol symbol, String marginType) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/marginType";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            params.put("marginType", marginType);
            signedRequest(endpoint, "POST", params);
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Set margin type failed", e);
        }
    }

    /**
     * Binance futures position mode query.
     * @return true when hedge mode (dual-side) is enabled, false means one-way mode.
     */
    public boolean isDualSidePositionEnabled() throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/positionSide/dual";
            String response = signedRequest(endpoint, "GET", null);
            JsonNode json = objectMapper.readTree(response);
            JsonNode dualNode = json.path("dualSidePosition");
            if (dualNode.isBoolean()) {
                return dualNode.asBoolean();
            }
            String raw = dualNode.asText("false");
            return "true".equalsIgnoreCase(raw);
        } catch (IOException e) {
            throw new ExchangeException(
                    ExchangeException.ErrorCode.API_ERROR,
                    "Get position mode failed: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Normalize market quantity by symbol-specific MARKET_LOT_SIZE/LOT_SIZE.
     */
    public BigDecimal normalizeMarketQuantity(Symbol symbol, BigDecimal rawQuantity) throws ExchangeException {
        SymbolRules rules = getSymbolRules(symbol);
        BigDecimal step = rules.marketStepSize.compareTo(BigDecimal.ZERO) > 0
                ? rules.marketStepSize
                : rules.lotStepSize;
        BigDecimal minQty = rules.marketMinQty.compareTo(BigDecimal.ZERO) > 0
                ? rules.marketMinQty
                : rules.lotMinQty;
        return normalizeQuantity(rawQuantity, step, minQty, symbol, "MARKET");
    }

    /**
     * Normalize limit quantity by symbol-specific LOT_SIZE.
     */
    public BigDecimal normalizeLimitQuantity(Symbol symbol, BigDecimal rawQuantity) throws ExchangeException {
        SymbolRules rules = getSymbolRules(symbol);
        return normalizeQuantity(rawQuantity, rules.lotStepSize, rules.lotMinQty, symbol, "LIMIT");
    }

    /**
     * Normalize protective stop trigger price. SELL stops round up, BUY stops round down.
     */
    public BigDecimal normalizeStopPrice(Symbol symbol, Side closeSide, BigDecimal rawStopPrice) throws ExchangeException {
        SymbolRules rules = getSymbolRules(symbol);
        if (rawStopPrice == null || rawStopPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Invalid stop price: " + rawStopPrice);
        }
        RoundingMode mode = closeSide == Side.SELL ? RoundingMode.UP : RoundingMode.DOWN;
        BigDecimal normalized = normalizePrice(rawStopPrice, rules.tickSize, mode);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Stop price too small after normalization: " + rawStopPrice);
        }
        return normalized;
    }

    private BigDecimal normalizePrice(BigDecimal rawPrice, BigDecimal tickSize, RoundingMode mode) {
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return Decimal.scalePrice(rawPrice);
        }
        BigDecimal ticks = rawPrice.divide(tickSize, 0, mode);
        BigDecimal normalized = ticks.multiply(tickSize);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return normalized.stripTrailingZeros();
    }

    private BigDecimal normalizeQuantity(BigDecimal rawQty, BigDecimal stepSize, BigDecimal minQty,
                                         Symbol symbol, String orderType) throws ExchangeException {
        if (rawQty == null || rawQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Invalid quantity: " + rawQty);
        }

        BigDecimal normalized;
        if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal steps = rawQty.divide(stepSize, 0, RoundingMode.DOWN);
            normalized = steps.multiply(stepSize);
        } else {
            normalized = Decimal.scaleQuantity(rawQty);
        }

        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Quantity rounded to zero: raw=" + rawQty + ", step=" + stepSize);
        }
        if (minQty != null && minQty.compareTo(BigDecimal.ZERO) > 0 && normalized.compareTo(minQty) < 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Quantity below " + orderType + " minQty: qty=" + normalized + ", minQty=" + minQty
                            + ", symbol=" + symbol);
        }
        return normalized.stripTrailingZeros();
    }

    private SymbolRules getSymbolRules(Symbol symbol) throws ExchangeException {
        String pair = symbol.toPairString();
        SymbolRules cached = symbolRulesCache.get(pair);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis <= SYMBOL_RULE_CACHE_TTL_MS) {
            return cached;
        }

        synchronized (symbolRulesCache) {
            cached = symbolRulesCache.get(pair);
            if (cached != null && now - cached.loadedAtMillis <= SYMBOL_RULE_CACHE_TTL_MS) {
                return cached;
            }
            SymbolRules fresh = fetchSymbolRules(symbol);
            symbolRulesCache.put(pair, fresh);
            return fresh;
        }
    }

    private SymbolRules fetchSymbolRules(Symbol symbol) throws ExchangeException {
        String endpoint = "/fapi/v1/exchangeInfo?symbol=" + symbol.toPairString();
        try {
            String response = publicRequest(endpoint);
            JsonNode root = objectMapper.readTree(response);
            JsonNode symbolsNode = root.path("symbols");
            if (!symbolsNode.isArray() || symbolsNode.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.INVALID_SYMBOL,
                        "Symbol rules not found for " + symbol);
            }

            JsonNode symbolNode = symbolsNode.get(0);
            BigDecimal tickSize = new BigDecimal("0.01");
            BigDecimal lotStepSize = new BigDecimal("0.001");
            BigDecimal marketStepSize = lotStepSize;
            BigDecimal lotMinQty = BigDecimal.ZERO;
            BigDecimal marketMinQty = BigDecimal.ZERO;
            BigDecimal minNotional = BigDecimal.ZERO;

            JsonNode filters = symbolNode.path("filters");
            if (filters.isArray()) {
                for (JsonNode filter : filters) {
                    String type = filter.path("filterType").asText("");
                    if ("PRICE_FILTER".equalsIgnoreCase(type)) {
                        tickSize = parsePositiveDecimal(filter.path("tickSize").asText(), tickSize);
                    } else if ("LOT_SIZE".equalsIgnoreCase(type)) {
                        lotStepSize = parsePositiveDecimal(filter.path("stepSize").asText(), lotStepSize);
                        lotMinQty = parsePositiveDecimal(filter.path("minQty").asText(), lotMinQty);
                    } else if ("MARKET_LOT_SIZE".equalsIgnoreCase(type)) {
                        marketStepSize = parsePositiveDecimal(filter.path("stepSize").asText(), marketStepSize);
                        marketMinQty = parsePositiveDecimal(filter.path("minQty").asText(), marketMinQty);
                    } else if ("MIN_NOTIONAL".equalsIgnoreCase(type) || "NOTIONAL".equalsIgnoreCase(type)) {
                        minNotional = parsePositiveDecimal(filter.path("notional").asText(), minNotional);
                    }
                }
            }

            if (marketStepSize.compareTo(BigDecimal.ZERO) <= 0) {
                marketStepSize = lotStepSize;
            }
            if (marketMinQty.compareTo(BigDecimal.ZERO) <= 0) {
                marketMinQty = lotMinQty;
            }

            return new SymbolRules(
                    tickSize,
                    lotStepSize,
                    marketStepSize,
                    lotMinQty,
                    marketMinQty,
                    minNotional,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR,
                    "Fetch symbol rules failed: " + symbol, e);
        }
    }

    private BigDecimal parsePositiveDecimal(String raw, BigDecimal fallback) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * Place exchange-hosted protective stop order.
     * Triggered by mark price and reduce-only to avoid accidental reverse position.
     */
    public String placeReduceOnlyStopMarketOrder(Symbol symbol, Side side,
                                                 BigDecimal stopPrice, BigDecimal quantity,
                                                 String clientOrderId) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            BigDecimal normalizedStop = normalizeStopPrice(symbol, side, stopPrice);
            BigDecimal normalizedQty = normalizeMarketQuantity(symbol, quantity);
            if (rules.minNotional.compareTo(BigDecimal.ZERO) > 0
                    && normalizedStop.multiply(normalizedQty).compareTo(rules.minNotional) < 0) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Protective stop notional below minNotional: " + normalizedStop.multiply(normalizedQty));
            }

            String endpoint = "/fapi/v1/order";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            params.put("side", side == Side.BUY ? "BUY" : "SELL");
            params.put("type", "STOP_MARKET");
            params.put("stopPrice", normalizedStop.toPlainString());
            params.put("workingType", "MARK_PRICE");
            params.put("reduceOnly", "true");
            params.put("quantity", normalizedQty.toPlainString());
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                params.put("newClientOrderId", clientOrderId);
            }

            String response = signedRequest(endpoint, "POST", params);
            JsonNode json = objectMapper.readTree(response);
            return json.path("orderId").asText();
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Place protective stop failed", e);
        }
    }

    /**
     * Best-effort cleanup for stale reduce-only stop orders on one-way mode.
     * Useful after process restart/orphan-position adoption where local order IDs are missing.
     */
    public int cancelReduceOnlyStopOrders(Symbol symbol) throws ExchangeException {
        try {
            String listEndpoint = "/fapi/v1/openOrders";
            Map<String, String> listParams = new HashMap<>();
            listParams.put("symbol", symbol.toPairString());
            String listResponse = signedRequest(listEndpoint, "GET", listParams);
            JsonNode orders = objectMapper.readTree(listResponse);
            if (!orders.isArray()) {
                return 0;
            }

            int cancelled = 0;
            for (JsonNode node : orders) {
                boolean reduceOnly = node.path("reduceOnly").asBoolean(false);
                String type = node.path("type").asText("");
                if (!reduceOnly) {
                    continue;
                }
                if (!"STOP_MARKET".equalsIgnoreCase(type) && !"STOP".equalsIgnoreCase(type)) {
                    continue;
                }
                String orderId = node.path("orderId").asText("");
                if (orderId.isBlank()) {
                    continue;
                }
                Map<String, String> cancelParams = new HashMap<>();
                cancelParams.put("symbol", symbol.toPairString());
                cancelParams.put("orderId", orderId);
                signedRequest("/fapi/v1/order", "DELETE", cancelParams);
                cancelled++;
            }
            return cancelled;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Cancel stale protective stops failed", e);
        }
    }

    @Override
    public AccountInfo getAccountInfo() throws ExchangeException {
        try {
            String endpoint = "/fapi/v2/account";
            String response = signedRequest(endpoint, "GET", null);
            JsonNode json = objectMapper.readTree(response);

            BigDecimal totalWalletBalance = new BigDecimal(json.path("totalWalletBalance").asText("0"));
            BigDecimal availableBalance = new BigDecimal(json.path("availableBalance").asText("0"));
            BigDecimal unrealizedPnl = new BigDecimal(json.path("totalUnrealizedProfit").asText("0"));

            return new AccountInfo(
                    Decimal.scalePrice(totalWalletBalance),
                    Decimal.scalePrice(availableBalance),
                    Decimal.scalePrice(unrealizedPnl),
                    BigDecimal.ZERO
            );

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get account info failed", e);
        }
    }

    @Override
    public Ticker getTicker(Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/ticker/bookTicker?symbol=" + symbol.toPairString();
            String response = publicRequest(endpoint);
            JsonNode json = objectMapper.readTree(response);

            BigDecimal bidPrice = new BigDecimal(json.path("bidPrice").asText("0"));
            BigDecimal askPrice = new BigDecimal(json.path("askPrice").asText("0"));

            // Use mark price for stop/risk checks.
            String markEndpoint = "/fapi/v1/premiumIndex?symbol=" + symbol.toPairString();
            String markResponse = publicRequest(markEndpoint);
            JsonNode markJson = objectMapper.readTree(markResponse);
            BigDecimal markPrice = new BigDecimal(markJson.path("markPrice").asText("0"));
            BigDecimal lastPrice = markPrice.compareTo(BigDecimal.ZERO) > 0
                    ? markPrice
                    : bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);

            return new Ticker(
                    symbol,
                    Decimal.scalePrice(bidPrice),
                    Decimal.scalePrice(askPrice),
                    Decimal.scalePrice(lastPrice),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Instant.now()
            );

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get ticker failed", e);
        }
    }

    @Override
    public List<KLine> getKLines(Symbol symbol, Interval interval, int limit, Long endTime) throws ExchangeException {
        try {
            StringBuilder url = new StringBuilder("/fapi/v1/klines?")
                    .append("symbol=").append(symbol.toPairString())
                    .append("&interval=").append(interval.getCode())
                    .append("&limit=").append(Math.min(limit, 1500));

            if (endTime != null) {
                url.append("&endTime=").append(endTime);
            }

            String response = publicRequest(url.toString());
            JsonNode jsonArray = objectMapper.readTree(response);

            List<KLine> kLines = new ArrayList<>();
            Instant now = Instant.now();
            for (JsonNode node : jsonArray) {
                Instant openTime = Instant.ofEpochMilli(node.get(0).asLong());
                BigDecimal open = new BigDecimal(node.get(1).asText());
                BigDecimal high = new BigDecimal(node.get(2).asText());
                BigDecimal low = new BigDecimal(node.get(3).asText());
                BigDecimal close = new BigDecimal(node.get(4).asText());
                BigDecimal volume = new BigDecimal(node.get(5).asText());
                Instant closeTime = Instant.ofEpochMilli(node.get(6).asLong());
                if (closeTime.isAfter(now)) {
                    continue;
                }
                BigDecimal quoteVolume = new BigDecimal(node.get(7).asText());
                long trades = node.get(8).asLong();

                kLines.add(new KLine(symbol, interval, openTime, closeTime,
                        Decimal.scalePrice(open),
                        Decimal.scalePrice(high),
                        Decimal.scalePrice(low),
                        Decimal.scalePrice(close),
                        Decimal.scaleQuantity(volume),
                        Decimal.scalePrice(quoteVolume),
                        trades));
            }

            return kLines;

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get klines failed", e);
        }
    }

    @Override
    public String placeOrder(Order order) throws ExchangeException {
        try {
            BigDecimal normalizedQty = order.getType() == OrderType.MARKET
                    ? normalizeMarketQuantity(order.getSymbol(), order.getQuantity())
                    : normalizeLimitQuantity(order.getSymbol(), order.getQuantity());
            BigDecimal normalizedPrice = null;
            if (order.getType() == OrderType.LIMIT) {
                SymbolRules rules = getSymbolRules(order.getSymbol());
                normalizedPrice = normalizePrice(order.getPrice(), rules.tickSize, RoundingMode.DOWN);
                if (normalizedPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                            "Invalid limit price after normalization: " + order.getPrice());
                }
            }

            String endpoint = "/fapi/v1/order";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", order.getSymbol().toPairString());
            params.put("side", order.getSide() == Side.BUY ? "BUY" : "SELL");
            params.put("type", order.getType() == OrderType.LIMIT ? "LIMIT" : "MARKET");
            params.put("quantity", normalizedQty.toPlainString());
            params.put("newClientOrderId", order.getOrderId());
            if (order.isReduceOnly()) {
                params.put("reduceOnly", "true");
            }

            if (order.getType() == OrderType.LIMIT) {
                params.put("timeInForce", "GTC");
                params.put("price", normalizedPrice.toPlainString());
            }

            String response = signedRequest(endpoint, "POST", params);
            JsonNode json = objectMapper.readTree(response);
            return json.path("orderId").asText();
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Place order failed", e);
        }
    }

    @Override
    public boolean cancelOrder(String orderId, Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/order";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            params.put("orderId", orderId);
            signedRequest(endpoint, "DELETE", params);
            return true;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Cancel order failed", e);
        }
    }

    @Override
    public Order getOrder(String orderId, Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/order";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            if (isDigits(orderId)) {
                params.put("orderId", orderId);
            } else {
                // Fallback for locally generated client order id.
                params.put("origClientOrderId", orderId);
            }
            String response = signedRequest(endpoint, "GET", params);
            JsonNode json = objectMapper.readTree(response);

            Side side = "BUY".equalsIgnoreCase(json.path("side").asText()) ? Side.BUY : Side.SELL;
            OrderType type = "LIMIT".equalsIgnoreCase(json.path("type").asText()) ? OrderType.LIMIT : OrderType.MARKET;
            BigDecimal origQty = new BigDecimal(json.path("origQty").asText("0"));
            BigDecimal price = new BigDecimal(json.path("price").asText("0"));
            BigDecimal executedQty = new BigDecimal(json.path("executedQty").asText("0"));
            BigDecimal avgPrice = new BigDecimal(json.path("avgPrice").asText("0"));

            OrderStatus status;
            String statusText = json.path("status").asText();
            if ("FILLED".equalsIgnoreCase(statusText)) {
                status = OrderStatus.FILLED;
            } else if ("PARTIALLY_FILLED".equalsIgnoreCase(statusText)) {
                status = OrderStatus.PARTIAL;
            } else if ("CANCELED".equalsIgnoreCase(statusText) || "CANCELLED".equalsIgnoreCase(statusText)) {
                status = OrderStatus.CANCELLED;
            } else {
                status = OrderStatus.SUBMITTED;
            }

            Order order = Order.builder()
                    .orderId(orderId)
                    .symbol(symbol)
                    .side(side)
                    .type(type)
                    .quantity(origQty)
                    .price(price)
                    .stopLoss(BigDecimal.ZERO)
                    .takeProfit(BigDecimal.ZERO)
                    .strategyId("EXCHANGE")
                    .reduceOnly(false)
                    .build();
            order.setStatus(status);
            order.setFilledQuantity(executedQty);
            order.setAvgFillPrice(avgPrice);
            return order;
        } catch (IOException e) {
            throw new ExchangeException(
                    ExchangeException.ErrorCode.API_ERROR,
                    "Get order failed: " + e.getMessage(),
                    e
            );
        }
    }

    @Override
    public List<Position> getOpenPositions(Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v2/positionRisk";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol.toPairString());
            String response = signedRequest(endpoint, "GET", params);
            JsonNode jsonArray = objectMapper.readTree(response);

            List<Position> positions = new ArrayList<>();
            for (JsonNode node : jsonArray) {
                BigDecimal positionAmt = new BigDecimal(node.path("positionAmt").asText("0"));
                if (positionAmt.compareTo(BigDecimal.ZERO) != 0) {
                    PositionSide side = positionAmt.compareTo(BigDecimal.ZERO) > 0
                            ? PositionSide.LONG : PositionSide.SHORT;

                    BigDecimal entryPrice = new BigDecimal(node.path("entryPrice").asText("0"));
                    BigDecimal leverage = new BigDecimal(node.path("leverage").asText("1"));
                    Position pos = new Position(
                            symbol,
                            side,
                            Decimal.scalePrice(entryPrice),
                            Decimal.scaleQuantity(positionAmt.abs()),
                            null,
                            leverage
                    );

                    positions.add(pos);
                }
            }

            return positions;

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get positions failed", e);
        }
    }

    @Override
    public List<Trade> getTradeHistory(Symbol symbol, int limit) throws ExchangeException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener) {
        String stream = symbol.toPairString().toLowerCase() + "@kline_" + interval.getCode();
        if (streamSubscriptions.containsKey(stream)) {
            unsubscribeKLine(symbol, interval);
        }
        StreamSubscription subscription = new StreamSubscription(stream, symbol, interval, listener, false);
        streamSubscriptions.put(stream, subscription);
        shuttingDown = false;
        openStream(subscription);
    }

    @Override
    public void subscribeTicker(Symbol symbol, MarketDataListener listener) {
        String stream = symbol.toPairString().toLowerCase() + "@bookTicker";
        if (streamSubscriptions.containsKey(stream)) {
            unsubscribeTicker(symbol);
        }
        StreamSubscription subscription = new StreamSubscription(stream, symbol, null, listener, true);
        streamSubscriptions.put(stream, subscription);
        shuttingDown = false;
        openStream(subscription);
    }

    private void openStream(StreamSubscription subscription) {
        String wsUrl = wsBaseUrl + "/" + subscription.stream;
        WebSocket ws = httpClient.newWebSocket(createRequest(wsUrl), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                subscription.reconnectAttempts = 0;
                cancelReconnect(subscription.stream);
                stopFallbackPolling(subscription.stream);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    if (subscription.tickerStream) {
                        Ticker ticker = parseTicker(subscription.symbol, text);
                        subscription.listener.onTicker(ticker);
                    } else {
                        KLine kLine = parseKLine(subscription.symbol, subscription.interval, text);
                        if (kLine != null) {
                            subscription.listener.onKLine(kLine);
                        }
                    }
                } catch (Exception e) {
                    subscription.listener.onError(e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                handleStreamFailure(subscription.stream, webSocket, t);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                handleStreamFailure(subscription.stream, webSocket, null);
            }
        });
        webSockets.put(subscription.stream, ws);
    }

    private KLine parseKLine(Symbol symbol, Interval interval, String text) throws IOException {
        JsonNode json = objectMapper.readTree(text);
        JsonNode kline = json.get("k");
        if (kline == null || kline.isNull()) {
            return null;
        }
        if (!isFinalKLineEvent(kline)) {
            return null;
        }
        Instant openTime = Instant.ofEpochMilli(kline.get("t").asLong());
        BigDecimal open = new BigDecimal(kline.get("o").asText());
        BigDecimal high = new BigDecimal(kline.get("h").asText());
        BigDecimal low = new BigDecimal(kline.get("l").asText());
        BigDecimal close = new BigDecimal(kline.get("c").asText());
        BigDecimal volume = new BigDecimal(kline.get("v").asText());
        Instant closeTime = Instant.ofEpochMilli(kline.get("T").asLong());
        return new KLine(symbol, interval, openTime, closeTime,
                Decimal.scalePrice(open),
                Decimal.scalePrice(high),
                Decimal.scalePrice(low),
                Decimal.scalePrice(close),
                Decimal.scaleQuantity(volume),
                BigDecimal.ZERO,
                0);
    }

    private boolean isFinalKLineEvent(JsonNode kline) {
        JsonNode closedNode = kline.get("x");
        if (closedNode == null || closedNode.isNull()) {
            return true;
        }
        return closedNode.asBoolean(false);
    }

    private Ticker parseTicker(Symbol symbol, String text) throws IOException {
        JsonNode json = objectMapper.readTree(text);
        BigDecimal bidPrice = new BigDecimal(json.get("b").asText());
        BigDecimal askPrice = new BigDecimal(json.get("a").asText());
        return new Ticker(
                symbol,
                Decimal.scalePrice(bidPrice),
                Decimal.scalePrice(askPrice),
                bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now()
        );
    }

    private void handleStreamFailure(String stream, WebSocket failedSocket, Throwable cause) {
        WebSocket current = webSockets.get(stream);
        if (current != failedSocket) {
            return;
        }
        webSockets.remove(stream);
        refreshConnectedFlag();

        StreamSubscription subscription = streamSubscriptions.get(stream);
        if (subscription == null || shuttingDown) {
            return;
        }

        if (cause != null) {
            subscription.listener.onError(cause);
        }
        startFallbackPolling(subscription);
        scheduleReconnect(subscription);
    }

    private void scheduleReconnect(StreamSubscription subscription) {
        reconnectTasks.compute(subscription.stream, (key, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            long delayMs = computeReconnectDelay(subscription.reconnectAttempts++);
            return wsExecutor.schedule(() -> {
                reconnectTasks.remove(subscription.stream);
                if (shuttingDown || !streamSubscriptions.containsKey(subscription.stream)) {
                    return;
                }
                openStream(subscription);
            }, delayMs, TimeUnit.MILLISECONDS);
        });
    }

    private long computeReconnectDelay(int attempts) {
        int safeAttempts = Math.max(0, Math.min(attempts, 6));
        long delay = WS_RECONNECT_BASE_DELAY_MS * (1L << safeAttempts);
        return Math.min(delay, WS_RECONNECT_MAX_DELAY_MS);
    }

    private void startFallbackPolling(StreamSubscription subscription) {
        fallbackPollTasks.compute(subscription.stream, (key, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return wsExecutor.scheduleAtFixedRate(() -> runFallbackPoll(subscription),
                    REST_FALLBACK_POLL_MS,
                    REST_FALLBACK_POLL_MS,
                    TimeUnit.MILLISECONDS);
        });
    }

    private void runFallbackPoll(StreamSubscription subscription) {
        if (shuttingDown || !streamSubscriptions.containsKey(subscription.stream)) {
            stopFallbackPolling(subscription.stream);
            return;
        }
        if (webSockets.containsKey(subscription.stream)) {
            return;
        }

        try {
            if (subscription.tickerStream) {
                Ticker ticker = getTicker(subscription.symbol);
                subscription.listener.onTicker(ticker);
            } else {
                List<KLine> latest = getKLines(subscription.symbol, subscription.interval, 2, null);
                if (!latest.isEmpty()) {
                    subscription.listener.onKLine(latest.get(latest.size() - 1));
                }
            }
        } catch (Exception e) {
            subscription.listener.onError(e);
        }
    }

    private void cancelReconnect(String stream) {
        ScheduledFuture<?> task = reconnectTasks.remove(stream);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void stopFallbackPolling(String stream) {
        ScheduledFuture<?> task = fallbackPollTasks.remove(stream);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void refreshConnectedFlag() {
        connected = !webSockets.isEmpty();
    }

    @Override
    public void unsubscribeKLine(Symbol symbol, Interval interval) {
        String stream = symbol.toPairString().toLowerCase() + "@kline_" + interval.getCode();
        streamSubscriptions.remove(stream);
        cancelReconnect(stream);
        stopFallbackPolling(stream);
        WebSocket ws = webSockets.remove(stream);
        if (ws != null) {
            ws.close(1000, "Unsubscribe");
        }
        refreshConnectedFlag();
    }

    @Override
    public void unsubscribeTicker(Symbol symbol) {
        String stream = symbol.toPairString().toLowerCase() + "@bookTicker";
        streamSubscriptions.remove(stream);
        cancelReconnect(stream);
        stopFallbackPolling(stream);
        WebSocket ws = webSockets.remove(stream);
        if (ws != null) {
            ws.close(1000, "Unsubscribe");
        }
        refreshConnectedFlag();
    }

    @Override
    public void connect() throws ExchangeException {
        shuttingDown = false;
        connected = true;
    }

    @Override
    public void disconnect() {
        shuttingDown = true;
        reconnectTasks.values().forEach(task -> task.cancel(false));
        reconnectTasks.clear();
        fallbackPollTasks.values().forEach(task -> task.cancel(false));
        fallbackPollTasks.clear();
        streamSubscriptions.clear();
        webSockets.values().forEach(ws -> ws.close(1000, "Disconnect"));
        webSockets.clear();
        connected = false;
        shuttingDown = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private Request createRequest(String url) {
        return new Request.Builder()
                .url(url)
                .build();
    }

    private String publicRequest(String endpoint) throws IOException {
        Request request = new Request.Builder()
                .url(restBaseUrl + endpoint)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private String signedRequest(String endpoint, String method, Map<String, String> params) throws IOException {
        Map<String, String> allParams = new TreeMap<>();
        if (params != null) {
            allParams.putAll(params);
        }
        allParams.put("timestamp", String.valueOf(System.currentTimeMillis()));

        String queryString = buildQueryString(allParams);
        String signature = hmacSHA256(queryString, secretKey);
        String url = restBaseUrl + endpoint + "?" + queryString + "&signature=" + signature;

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey);

        if ("POST".equalsIgnoreCase(method)) {
            requestBuilder.post(RequestBody.create("", null));
        } else if ("DELETE".equalsIgnoreCase(method)) {
            requestBuilder.delete(RequestBody.create("", null));
        } else {
            requestBuilder.get();
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.body().string());
            }
            return response.body().string();
        }
    }

    private String firstNonBlank(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        return candidate.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = firstNonBlank(baseUrl, PROD_REST_BASE_URL);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizeWsBaseUrl(String wsUrl) {
        String value = firstNonBlank(wsUrl, PROD_WS_BASE_URL);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private boolean isDigits(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String hmacSHA256(String data, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec =
                    new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Sign failed", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
