package com.trade.quant.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.quant.core.*;
import com.trade.quant.market.MarketDataListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * OKX v5 swap exchange implementation.
 * Notes:
 * - This implementation targets USDT-margined SWAP instruments.
 * - Internal quantity unit stays in base asset (e.g. BTC), and will be converted
 *   to OKX contract quantity (sz) by instrument ctVal.
 */
public class OkxExchange implements Exchange, ProtectiveStopCapableExchange {

    private static final String PROD_BASE_URL = "https://www.okx.com";
    private static final String PROD_WS_PUBLIC_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String PROD_WS_BUSINESS_URL = "wss://ws.okx.com:8443/ws/v5/business";
    private static final String DEMO_BASE_URL = "https://www.okx.com";
    private static final String DEMO_WS_PUBLIC_URL = "wss://wspap.okx.com:8443/ws/v5/public";
    private static final String DEMO_WS_BUSINESS_URL = "wss://wspap.okx.com:8443/ws/v5/business";
    private static final long SYMBOL_RULE_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long WS_RECONNECT_BASE_DELAY_MS = 1000L;
    private static final long WS_RECONNECT_MAX_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long REST_FALLBACK_POLL_MS = 2000L;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final String ALGO_ORDER_PREFIX = "okx-algo:";
    private static final int CANCEL_ALGO_BATCH_SIZE = 20;

    private String apiKey;
    private String secretKey;
    private String passphrase;
    private String restBaseUrl;
    private String wsPublicUrl;
    private String wsBusinessUrl;
    private volatile boolean demoTradingEnabled;
    private OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, SymbolRules> symbolRulesCache;
    private final Map<String, WebSocket> webSockets;
    private final Map<String, StreamSubscription> streamSubscriptions;
    private final Map<String, ScheduledFuture<?>> reconnectTasks;
    private final Map<String, ScheduledFuture<?>> fallbackPollTasks;
    private final ScheduledExecutorService wsExecutor;
    private volatile boolean connected;
    private volatile boolean shuttingDown;

    private static final class SymbolRules {
        private final String instId;
        private final BigDecimal tickSize;
        private final BigDecimal lotStepSize;
        private final BigDecimal minContracts;
        private final BigDecimal contractValue;
        private final long loadedAtMillis;

        private SymbolRules(String instId,
                            BigDecimal tickSize,
                            BigDecimal lotStepSize,
                            BigDecimal minContracts,
                            BigDecimal contractValue,
                            long loadedAtMillis) {
            this.instId = instId;
            this.tickSize = tickSize;
            this.lotStepSize = lotStepSize;
            this.minContracts = minContracts;
            this.contractValue = contractValue;
            this.loadedAtMillis = loadedAtMillis;
        }
    }

    private static final class StreamSubscription {
        private final String stream;
        private final String channel;
        private final Symbol symbol;
        private final Interval interval;
        private final MarketDataListener listener;
        private final boolean tickerStream;
        private int reconnectAttempts;

        private StreamSubscription(String stream, String channel, Symbol symbol, Interval interval,
                                   MarketDataListener listener, boolean tickerStream) {
            this.stream = stream;
            this.channel = channel;
            this.symbol = symbol;
            this.interval = interval;
            this.listener = listener;
            this.tickerStream = tickerStream;
            this.reconnectAttempts = 0;
        }
    }

    public OkxExchange() {
        this.restBaseUrl = PROD_BASE_URL;
        this.wsPublicUrl = PROD_WS_PUBLIC_URL;
        this.wsBusinessUrl = PROD_WS_BUSINESS_URL;
        this.demoTradingEnabled = false;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.symbolRulesCache = new ConcurrentHashMap<>();
        this.webSockets = new ConcurrentHashMap<>();
        this.streamSubscriptions = new ConcurrentHashMap<>();
        this.reconnectTasks = new ConcurrentHashMap<>();
        this.fallbackPollTasks = new ConcurrentHashMap<>();
        this.wsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "okx-ws-supervisor");
            t.setDaemon(true);
            return t;
        });
        this.connected = false;
        this.shuttingDown = false;
    }

    public void configureDemoTrading(boolean enabled, String restBaseUrlOverride, String wsPublicUrlOverride) {
        configureDemoTrading(enabled, restBaseUrlOverride, wsPublicUrlOverride, "");
    }

    public void configureDemoTrading(boolean enabled,
                                     String restBaseUrlOverride,
                                     String wsPublicUrlOverride,
                                     String wsBusinessUrlOverride) {
        this.demoTradingEnabled = enabled;
        if (enabled) {
            this.restBaseUrl = normalizeBaseUrl(firstNonBlank(restBaseUrlOverride, DEMO_BASE_URL));
            this.wsPublicUrl = normalizeWsBaseUrl(firstNonBlank(wsPublicUrlOverride, DEMO_WS_PUBLIC_URL));
            this.wsBusinessUrl = normalizeWsBaseUrl(firstNonBlank(wsBusinessUrlOverride, DEMO_WS_BUSINESS_URL));
        } else {
            this.restBaseUrl = normalizeBaseUrl(firstNonBlank(restBaseUrlOverride, PROD_BASE_URL));
            this.wsPublicUrl = normalizeWsBaseUrl(firstNonBlank(wsPublicUrlOverride, PROD_WS_PUBLIC_URL));
            this.wsBusinessUrl = normalizeWsBaseUrl(firstNonBlank(wsBusinessUrlOverride, PROD_WS_BUSINESS_URL));
        }
    }

    public boolean isDemoTradingEnabled() {
        return demoTradingEnabled;
    }

    @Override
    public String getName() {
        return demoTradingEnabled ? "OKX-Demo" : "OKX";
    }

    @Override
    public void setApiKey(String apiKey, String secretKey, String passphrase) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
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

    /**
     * OKX account position mode query.
     * @return true if account is in hedge mode (long_short_mode), false for one-way (net_mode).
     */
    public boolean isLongShortModeEnabled() throws ExchangeException {
        try {
            JsonNode root = privateRequestWithRetry("/api/v5/account/config", null);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR,
                        "Get account config returned empty data");
            }
            String posMode = data.get(0).path("posMode").asText("");
            return "long_short_mode".equalsIgnoreCase(posMode);
        } catch (IOException e) {
            throw new ExchangeException(
                    ExchangeException.ErrorCode.API_ERROR,
                    "Get account config failed: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Set leverage for swap instrument.
     */
    public void setLeverage(Symbol symbol, int leverage) throws ExchangeException {
        if (leverage <= 0) {
            return;
        }
        try {
            SymbolRules rules = getSymbolRules(symbol);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instId", rules.instId);
            body.put("lever", String.valueOf(leverage));
            body.put("mgnMode", resolveTdMode());
            privateRequest("/api/v5/account/set-leverage", "POST", null, body);
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Set leverage failed", e);
        }
    }

    @Override
    public BigDecimal normalizeMarketQuantity(Symbol symbol, BigDecimal rawQuantity) throws ExchangeException {
        SymbolRules rules = getSymbolRules(symbol);
        BigDecimal contracts = normalizeContracts(rawQuantity, rules);
        return contractsToBaseQuantity(contracts, rules).stripTrailingZeros();
    }

    @Override
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

    @Override
    public String placeReduceOnlyStopMarketOrder(Symbol symbol,
                                                 Side side,
                                                 BigDecimal stopPrice,
                                                 BigDecimal quantity,
                                                 String clientOrderId) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            BigDecimal normalizedStop = normalizeStopPrice(symbol, side, stopPrice);
            BigDecimal contracts = normalizeContracts(quantity, rules);
            BigDecimal normalizedQty = contractsToBaseQuantity(contracts, rules);
            BigDecimal minNotional = resolveMinProtectiveStopNotional(rules, normalizedStop);
            BigDecimal stopNotional = normalizedStop.multiply(normalizedQty);
            if (minNotional.compareTo(BigDecimal.ZERO) > 0 && stopNotional.compareTo(minNotional) < 0) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Protective stop notional below minNotional: " + stopNotional
                                + ", minNotional=" + minNotional);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instId", rules.instId);
            body.put("tdMode", resolveTdMode());
            body.put("side", side == Side.BUY ? "buy" : "sell");
            body.put("ordType", "conditional");
            body.put("sz", contracts.toPlainString());
            body.put("reduceOnly", "true");
            body.put("slTriggerPx", normalizedStop.toPlainString());
            body.put("slOrdPx", "-1");
            body.put("slTriggerPxType", "mark");
            String algoClOrdId = trimClientOrderId(clientOrderId);
            if (algoClOrdId != null) {
                body.put("algoClOrdId", algoClOrdId);
            }

            // OKX trade APIs may return top-level code=1 with per-row sCode/sMsg details.
            JsonNode root = privateRequest("/api/v5/trade/order-algo", "POST", null, body, true);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Place protective stop returned empty data");
            }
            JsonNode row = data.get(0);
            String sCode = row.path("sCode").asText("0");
            if (!"0".equals(sCode)) {
                String sMsg = row.path("sMsg").asText("unknown");
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Place protective stop rejected: sCode=" + sCode + ", sMsg=" + sMsg);
            }
            String algoId = row.path("algoId").asText("");
            if (algoId.isBlank()) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Place protective stop returned empty algoId");
            }
            return toAlgoOrderId(algoId);
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR,
                    "Place protective stop failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int cancelReduceOnlyStopOrders(Symbol symbol) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("instId", rules.instId);
            query.put("ordType", "conditional");
            JsonNode root = privateRequest("/api/v5/trade/orders-algo-pending", "GET", query, null);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return 0;
            }

            List<Map<String, Object>> toCancel = new ArrayList<>();
            for (JsonNode row : data) {
                if (!isReduceOnlyAlgo(row)) {
                    continue;
                }
                String algoId = row.path("algoId").asText("");
                if (algoId.isBlank()) {
                    continue;
                }
                Map<String, Object> cancelItem = new LinkedHashMap<>();
                cancelItem.put("algoId", algoId);
                cancelItem.put("instId", rules.instId);
                toCancel.add(cancelItem);
            }

            if (toCancel.isEmpty()) {
                return 0;
            }

            int cancelled = 0;
            for (int i = 0; i < toCancel.size(); i += CANCEL_ALGO_BATCH_SIZE) {
                int end = Math.min(i + CANCEL_ALGO_BATCH_SIZE, toCancel.size());
                List<Map<String, Object>> batch = new ArrayList<>(toCancel.subList(i, end));
                JsonNode cancelRoot = privateRequest("/api/v5/trade/cancel-algos", "POST", null, batch, true);
                JsonNode cancelData = cancelRoot.path("data");
                if (!cancelData.isArray()) {
                    continue;
                }
                for (JsonNode item : cancelData) {
                    if ("0".equals(item.path("sCode").asText("0"))) {
                        cancelled++;
                    }
                }
            }
            return cancelled;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR,
                    "Cancel stale protective stops failed", e);
        }
    }

    @Override
    public AccountInfo getAccountInfo() throws ExchangeException {
        try {
            Map<String, String> query = new HashMap<>();
            query.put("ccy", "USDT");
            JsonNode root = privateRequest("/api/v5/account/balance", "GET", query, null);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get account info returned empty data");
            }

            JsonNode account = data.get(0);
            BigDecimal totalEq = parseDecimal(account.path("totalEq").asText("0"), BigDecimal.ZERO);
            BigDecimal available = BigDecimal.ZERO;
            BigDecimal unrealized = BigDecimal.ZERO;

            JsonNode details = account.path("details");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    String ccy = detail.path("ccy").asText("");
                    if (!"USDT".equalsIgnoreCase(ccy)) {
                        continue;
                    }
                    available = pickFirstPositive(
                            parseDecimal(detail.path("availEq").asText("0"), BigDecimal.ZERO),
                            parseDecimal(detail.path("availBal").asText("0"), BigDecimal.ZERO),
                            parseDecimal(detail.path("cashBal").asText("0"), BigDecimal.ZERO)
                    );
                    unrealized = parseDecimal(detail.path("upl").asText("0"), BigDecimal.ZERO);
                    break;
                }
            }

            boolean fallbackToTotalEq = ConfigManager.getInstance()
                    .getBooleanProperty("okx.account.available.fallback.total.eq", false);
            if (available.compareTo(BigDecimal.ZERO) <= 0 && fallbackToTotalEq) {
                available = totalEq;
            }

            return new AccountInfo(
                    Decimal.scalePrice(totalEq),
                    Decimal.scalePrice(available),
                    Decimal.scalePrice(unrealized),
                    BigDecimal.ZERO
            );
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get account info failed", e);
        }
    }

    @Override
    public Ticker getTicker(Symbol symbol) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            Map<String, String> query = new HashMap<>();
            query.put("instId", rules.instId);

            JsonNode root = publicRequest("/api/v5/market/ticker", query);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get ticker returned empty data");
            }

            JsonNode node = data.get(0);
            BigDecimal bid = parseDecimal(node.path("bidPx").asText("0"), BigDecimal.ZERO);
            BigDecimal ask = parseDecimal(node.path("askPx").asText("0"), BigDecimal.ZERO);
            BigDecimal tradeLast = parseDecimal(node.path("last").asText("0"), BigDecimal.ZERO);
            BigDecimal markPrice = fetchMarkPrice(rules.instId);
            BigDecimal last = markPrice.compareTo(BigDecimal.ZERO) > 0 ? markPrice : tradeLast;

            if (bid.compareTo(BigDecimal.ZERO) <= 0 && last.compareTo(BigDecimal.ZERO) > 0) {
                bid = last;
            }
            if (ask.compareTo(BigDecimal.ZERO) <= 0 && last.compareTo(BigDecimal.ZERO) > 0) {
                ask = last;
            }
            if (last.compareTo(BigDecimal.ZERO) <= 0 && bid.compareTo(BigDecimal.ZERO) > 0 && ask.compareTo(BigDecimal.ZERO) > 0) {
                last = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            }

            return new Ticker(
                    symbol,
                    Decimal.scalePrice(bid),
                    Decimal.scalePrice(ask),
                    Decimal.scalePrice(last),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Instant.now()
            );
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get ticker failed", e);
        }
    }

    @Override
    public List<KLine> getKLines(Symbol symbol, Interval interval, int limit, Long endTime) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("instId", rules.instId);
            query.put("bar", toOkxBar(interval));
            query.put("limit", String.valueOf(Math.min(Math.max(limit, 1), 300)));
            if (endTime != null) {
                // OKX candles pagination anchor. Best-effort; startup path usually needs a single batch.
                query.put("before", String.valueOf(endTime));
            }

            JsonNode root = publicRequest("/api/v5/market/candles", query);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }

            List<KLine> kLines = new ArrayList<>();
            for (int i = data.size() - 1; i >= 0; i--) {
                JsonNode row = data.get(i);
                if (!row.isArray() || row.size() < 9) {
                    continue;
                }
                Instant openTime = Instant.ofEpochMilli(parseLong(row.get(0).asText("0"), 0L));
                Instant closeTime = openTime
                        .plus(interval.getMinutes(), ChronoUnit.MINUTES)
                        .minusMillis(1);
                BigDecimal open = parseDecimal(row.get(1).asText("0"), BigDecimal.ZERO);
                BigDecimal high = parseDecimal(row.get(2).asText("0"), BigDecimal.ZERO);
                BigDecimal low = parseDecimal(row.get(3).asText("0"), BigDecimal.ZERO);
                BigDecimal close = parseDecimal(row.get(4).asText("0"), BigDecimal.ZERO);
                BigDecimal volContracts = parseDecimal(row.get(5).asText("0"), BigDecimal.ZERO);
                BigDecimal volBase = parseDecimal(row.get(6).asText("0"), BigDecimal.ZERO);
                BigDecimal volQuote = parseDecimal(row.get(7).asText("0"), BigDecimal.ZERO);
                BigDecimal volume = volBase.compareTo(BigDecimal.ZERO) > 0
                        ? volBase
                        : volContracts.multiply(rules.contractValue);

                kLines.add(new KLine(
                        symbol,
                        interval,
                        openTime,
                        closeTime,
                        Decimal.scalePrice(open),
                        Decimal.scalePrice(high),
                        Decimal.scalePrice(low),
                        Decimal.scalePrice(close),
                        Decimal.scaleQuantity(volume),
                        Decimal.scalePrice(volQuote),
                        0L
                ));
            }
            return kLines;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get klines failed", e);
        }
    }

    @Override
    public String placeOrder(Order order) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(order.getSymbol());
            BigDecimal contracts = normalizeContracts(order.getQuantity(), rules);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instId", rules.instId);
            body.put("tdMode", resolveTdMode());
            body.put("side", order.getSide() == Side.BUY ? "buy" : "sell");
            body.put("ordType", order.getType() == OrderType.LIMIT ? "limit" : "market");
            body.put("sz", contracts.toPlainString());
            body.put("clOrdId", trimClientOrderId(order.getOrderId()));
            if (order.isReduceOnly()) {
                body.put("reduceOnly", true);
            }

            if (order.getType() == OrderType.LIMIT) {
                BigDecimal price = normalizePrice(order.getPrice(), rules.tickSize, RoundingMode.DOWN);
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                            "Invalid limit price after normalization: " + order.getPrice());
                }
                body.put("px", price.toPlainString());
            }

            // OKX trade APIs may return top-level code=1 with per-row sCode/sMsg details.
            JsonNode root = privateRequest("/api/v5/trade/order", "POST", null, body, true);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED, "Place order returned empty data");
            }

            JsonNode row = data.get(0);
            String sCode = row.path("sCode").asText("0");
            if (!"0".equals(sCode)) {
                String sMsg = row.path("sMsg").asText("unknown");
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Place order rejected: sCode=" + sCode + ", sMsg=" + sMsg);
            }
            String orderId = row.path("ordId").asText("");
            if (orderId == null || orderId.isBlank()) {
                throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                        "Place order returned empty ordId");
            }
            return orderId;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(
                    ExchangeException.ErrorCode.API_ERROR,
                    "Place order failed: " + e.getMessage(),
                    e
            );
        }
    }

    @Override
    public boolean cancelOrder(String orderId, Symbol symbol) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            String rawOrderId = unwrapAlgoOrderId(orderId);
            if (isAlgoOrderId(orderId)) {
                return cancelAlgoOrder(rawOrderId, rules.instId);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instId", rules.instId);
            body.put("ordId", rawOrderId);
            JsonNode root = privateRequest("/api/v5/trade/cancel-order", "POST", null, body, true);
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode row = data.get(0);
                String sCode = row.path("sCode").asText("0");
                if ("0".equals(sCode)) {
                    return true;
                }
            }
            // Fallback for algo stop IDs that may not be tagged with prefix.
            return cancelAlgoOrder(rawOrderId, rules.instId);
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Cancel order failed", e);
        }
    }

    @Override
    public Order getOrder(String orderId, Symbol symbol) throws ExchangeException {
        try {
            SymbolRules rules = getSymbolRules(symbol);
            JsonNode node = null;
            if (isDigits(orderId)) {
                node = queryOrderNode(rules.instId, "ordId", orderId);
                if (node == null) {
                    node = queryOrderNode(rules.instId, "clOrdId", trimClientOrderId(orderId));
                }
            } else {
                node = queryOrderNode(rules.instId, "clOrdId", trimClientOrderId(orderId));
                if (node == null) {
                    node = queryOrderNode(rules.instId, "ordId", orderId);
                }
            }

            if (node == null) {
                throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get order returned empty data");
            }
            Side side = "buy".equalsIgnoreCase(node.path("side").asText()) ? Side.BUY : Side.SELL;
            OrderType type = "limit".equalsIgnoreCase(node.path("ordType").asText()) ? OrderType.LIMIT : OrderType.MARKET;
            BigDecimal contracts = parseDecimal(node.path("sz").asText("0"), BigDecimal.ZERO);
            BigDecimal qty = contractsToBaseQuantity(contracts, rules);
            BigDecimal price = parseDecimal(node.path("px").asText("0"), BigDecimal.ZERO);
            BigDecimal filledContracts = parseDecimal(node.path("accFillSz").asText("0"), BigDecimal.ZERO);
            BigDecimal filledQty = contractsToBaseQuantity(filledContracts, rules);
            BigDecimal avgPrice = parseDecimal(node.path("avgPx").asText("0"), BigDecimal.ZERO);
            boolean reduceOnly = node.path("reduceOnly").asBoolean(false);

            String state = node.path("state").asText("");
            OrderStatus status;
            if ("filled".equalsIgnoreCase(state)) {
                status = OrderStatus.FILLED;
            } else if ("partially_filled".equalsIgnoreCase(state)) {
                status = OrderStatus.PARTIAL;
            } else if ("canceled".equalsIgnoreCase(state)) {
                status = OrderStatus.CANCELLED;
            } else {
                status = OrderStatus.SUBMITTED;
            }

            Order order = Order.builder()
                    .orderId(orderId)
                    .symbol(symbol)
                    .side(side)
                    .type(type)
                    .quantity(qty)
                    .price(price)
                    .stopLoss(BigDecimal.ZERO)
                    .takeProfit(BigDecimal.ZERO)
                    .strategyId("EXCHANGE")
                    .reduceOnly(reduceOnly)
                    .build();
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setAvgFillPrice(avgPrice);
            return order;
        } catch (ExchangeException e) {
            throw e;
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
            SymbolRules rules = getSymbolRules(symbol);
            Map<String, String> query = new HashMap<>();
            query.put("instType", "SWAP");
            query.put("instId", rules.instId);

            JsonNode root = privateRequestWithRetry("/api/v5/account/positions", query);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }

            List<Position> positions = new ArrayList<>();
            for (JsonNode node : data) {
                BigDecimal posContracts = parseDecimal(node.path("pos").asText("0"), BigDecimal.ZERO);
                if (posContracts.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                PositionSide side = parsePositionSide(node.path("posSide").asText(""), posContracts);
                BigDecimal entryPrice = parseDecimal(node.path("avgPx").asText("0"), BigDecimal.ZERO);
                BigDecimal leverage = parseDecimal(node.path("lever").asText("1"), BigDecimal.ONE);
                BigDecimal qty = contractsToBaseQuantity(posContracts.abs(), rules);
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                Position position = new Position(
                        symbol,
                        side,
                        Decimal.scalePrice(entryPrice),
                        Decimal.scaleQuantity(qty),
                        null,
                        leverage
                );
                positions.add(position);
            }
            return positions;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "Get positions failed", e);
        }
    }

    private JsonNode privateRequestWithRetry(String path, Map<String, String> query) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return privateRequest(path, "GET", query, null);
            } catch (IOException e) {
                last = e;
                if (!isRetryableServiceUnavailable(e) || attempt >= 3) {
                    throw e;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("Unknown request failure") : last;
    }

    @Override
    public List<Trade> getTradeHistory(Symbol symbol, int limit) {
        return List.of();
    }

    @Override
    public void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener) {
        String stream = buildKLineStream(symbol, interval);
        if (streamSubscriptions.containsKey(stream)) {
            unsubscribeKLine(symbol, interval);
        }
        StreamSubscription subscription = new StreamSubscription(
                stream,
                toOkxCandleChannel(interval),
                symbol,
                interval,
                listener,
                false
        );
        streamSubscriptions.put(stream, subscription);
        shuttingDown = false;
        openStream(subscription);
    }

    @Override
    public void subscribeTicker(Symbol symbol, MarketDataListener listener) {
        String stream = buildTickerStream(symbol);
        if (streamSubscriptions.containsKey(stream)) {
            unsubscribeTicker(symbol);
        }
        StreamSubscription subscription = new StreamSubscription(
                stream,
                "tickers",
                symbol,
                null,
                listener,
                true
        );
        streamSubscriptions.put(stream, subscription);
        shuttingDown = false;
        openStream(subscription);
    }

    @Override
    public void unsubscribeKLine(Symbol symbol, Interval interval) {
        String stream = buildKLineStream(symbol, interval);
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
        String stream = buildTickerStream(symbol);
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
    public void connect() {
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

    private String buildKLineStream(Symbol symbol, Interval interval) {
        return "kline:" + symbol.toPairString() + ":" + interval.getCode();
    }

    private String buildTickerStream(Symbol symbol) {
        return "ticker:" + symbol.toPairString();
    }

    private String toOkxCandleChannel(Interval interval) {
        return "candle" + toOkxBar(interval);
    }

    private void openStream(StreamSubscription subscription) {
        String wsUrl = subscription.tickerStream ? wsPublicUrl : wsBusinessUrl;
        WebSocket ws = httpClient.newWebSocket(createRequest(wsUrl), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                subscription.reconnectAttempts = 0;
                cancelReconnect(subscription.stream);
                stopFallbackPolling(subscription.stream);
                sendSubscribe(webSocket, subscription);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleWsMessage(subscription, text);
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

    private void sendSubscribe(WebSocket webSocket, StreamSubscription subscription) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("op", "subscribe");
            Map<String, String> arg = new LinkedHashMap<>();
            arg.put("channel", subscription.channel);
            arg.put("instId", toInstId(subscription.symbol));
            payload.put("args", List.of(arg));
            webSocket.send(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            subscription.listener.onError(e);
        }
    }

    private void handleWsMessage(StreamSubscription subscription, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            String event = root.path("event").asText("");
            if (!event.isBlank()) {
                if ("error".equalsIgnoreCase(event)) {
                    String msg = root.path("msg").asText("unknown");
                    subscription.listener.onError(new IOException("OKX WS error: " + msg));
                }
                return;
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return;
            }
            if (subscription.tickerStream) {
                Ticker ticker = parseTickerFromWs(subscription.symbol, data.get(0));
                if (ticker != null) {
                    subscription.listener.onTicker(ticker);
                }
            } else {
                KLine kLine = parseKLineFromWs(subscription.symbol, subscription.interval, data.get(0));
                if (kLine != null) {
                    subscription.listener.onKLine(kLine);
                }
            }
        } catch (Exception e) {
            subscription.listener.onError(e);
        }
    }

    private KLine parseKLineFromWs(Symbol symbol, Interval interval, JsonNode row) throws ExchangeException {
        if (row == null || !row.isArray() || row.size() < 6) {
            return null;
        }
        SymbolRules rules = getSymbolRules(symbol);
        Instant openTime = Instant.ofEpochMilli(parseLong(row.get(0).asText("0"), 0L));
        Instant closeTime = openTime.plus(interval.getMinutes(), ChronoUnit.MINUTES).minusMillis(1);
        BigDecimal open = parseDecimal(row.get(1).asText("0"), BigDecimal.ZERO);
        BigDecimal high = parseDecimal(row.get(2).asText("0"), BigDecimal.ZERO);
        BigDecimal low = parseDecimal(row.get(3).asText("0"), BigDecimal.ZERO);
        BigDecimal close = parseDecimal(row.get(4).asText("0"), BigDecimal.ZERO);
        BigDecimal volContracts = parseDecimal(row.get(5).asText("0"), BigDecimal.ZERO);
        BigDecimal volBase = row.size() > 6 ? parseDecimal(row.get(6).asText("0"), BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal volQuote = row.size() > 7 ? parseDecimal(row.get(7).asText("0"), BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal volume = volBase.compareTo(BigDecimal.ZERO) > 0
                ? volBase
                : volContracts.multiply(rules.contractValue);
        return new KLine(
                symbol,
                interval,
                openTime,
                closeTime,
                Decimal.scalePrice(open),
                Decimal.scalePrice(high),
                Decimal.scalePrice(low),
                Decimal.scalePrice(close),
                Decimal.scaleQuantity(volume),
                Decimal.scalePrice(volQuote),
                0L
        );
    }

    private Ticker parseTickerFromWs(Symbol symbol, JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        BigDecimal bid = parseDecimal(row.path("bidPx").asText("0"), BigDecimal.ZERO);
        BigDecimal ask = parseDecimal(row.path("askPx").asText("0"), BigDecimal.ZERO);
        BigDecimal mark = parseDecimal(row.path("markPx").asText("0"), BigDecimal.ZERO);
        BigDecimal tradeLast = parseDecimal(row.path("last").asText("0"), BigDecimal.ZERO);
        BigDecimal last = mark.compareTo(BigDecimal.ZERO) > 0 ? mark : tradeLast;
        if (last.compareTo(BigDecimal.ZERO) <= 0 && bid.compareTo(BigDecimal.ZERO) > 0 && ask.compareTo(BigDecimal.ZERO) > 0) {
            last = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
        if (bid.compareTo(BigDecimal.ZERO) <= 0 && last.compareTo(BigDecimal.ZERO) > 0) {
            bid = last;
        }
        if (ask.compareTo(BigDecimal.ZERO) <= 0 && last.compareTo(BigDecimal.ZERO) > 0) {
            ask = last;
        }
        return new Ticker(
                symbol,
                Decimal.scalePrice(bid),
                Decimal.scalePrice(ask),
                Decimal.scalePrice(last),
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
        scheduleReconnect(subscription);
        ensureFallbackPolling(subscription);
    }

    private ScheduledFuture<?> scheduleReconnect(StreamSubscription subscription) {
        cancelReconnect(subscription.stream);
        long delay = WS_RECONNECT_BASE_DELAY_MS * (1L << Math.min(subscription.reconnectAttempts, 10));
        if (delay > WS_RECONNECT_MAX_DELAY_MS) {
            delay = WS_RECONNECT_MAX_DELAY_MS;
        }
        subscription.reconnectAttempts++;
        ScheduledFuture<?> task = wsExecutor.schedule(() -> {
            if (shuttingDown) {
                return;
            }
            if (!streamSubscriptions.containsKey(subscription.stream)) {
                return;
            }
            openStream(subscription);
        }, delay, TimeUnit.MILLISECONDS);
        reconnectTasks.put(subscription.stream, task);
        return task;
    }

    private void cancelReconnect(String stream) {
        ScheduledFuture<?> task = reconnectTasks.remove(stream);
        if (task != null) {
            task.cancel(false);
        }
    }

    private ScheduledFuture<?> ensureFallbackPolling(StreamSubscription subscription) {
        if (fallbackPollTasks.containsKey(subscription.stream)) {
            return fallbackPollTasks.get(subscription.stream);
        }
        ScheduledFuture<?> task = wsExecutor.scheduleAtFixedRate(
                () -> runFallbackPoll(subscription),
                REST_FALLBACK_POLL_MS,
                REST_FALLBACK_POLL_MS,
                TimeUnit.MILLISECONDS
        );
        fallbackPollTasks.put(subscription.stream, task);
        return task;
    }

    private void stopFallbackPolling(String stream) {
        ScheduledFuture<?> task = fallbackPollTasks.remove(stream);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void runFallbackPoll(StreamSubscription subscription) {
        if (shuttingDown || !streamSubscriptions.containsKey(subscription.stream)) {
            return;
        }
        try {
            if (subscription.tickerStream) {
                subscription.listener.onTicker(getTicker(subscription.symbol));
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

    private void refreshConnectedFlag() {
        connected = !webSockets.isEmpty();
    }

    private Request createRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        if (demoTradingEnabled) {
            builder.addHeader("x-simulated-trading", "1");
        }
        return builder.build();
    }

    private String resolveTdMode() {
        ConfigManager cfg = ConfigManager.getInstance();
        String explicit = cfg.getProperty("okx.td.mode", "").trim().toLowerCase(Locale.ROOT);
        if ("cross".equals(explicit) || "isolated".equals(explicit) || "cash".equals(explicit)) {
            return explicit;
        }
        // Default to cross for OKX unless explicitly configured by okx.td.mode.
        // Do not implicitly reuse Binance-style live.margin.type here.
        return "cross";
    }

    private String firstNonBlank(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        return candidate.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = firstNonBlank(baseUrl, PROD_BASE_URL);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizeWsBaseUrl(String wsUrl) {
        String value = firstNonBlank(wsUrl, PROD_WS_PUBLIC_URL);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private SymbolRules getSymbolRules(Symbol symbol) throws ExchangeException {
        String key = symbol.toPairString();
        SymbolRules cached = symbolRulesCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis <= SYMBOL_RULE_CACHE_TTL_MS) {
            return cached;
        }

        synchronized (symbolRulesCache) {
            cached = symbolRulesCache.get(key);
            if (cached != null && now - cached.loadedAtMillis <= SYMBOL_RULE_CACHE_TTL_MS) {
                return cached;
            }
            SymbolRules fresh = fetchSymbolRules(symbol);
            symbolRulesCache.put(key, fresh);
            return fresh;
        }
    }

    private SymbolRules fetchSymbolRules(Symbol symbol) throws ExchangeException {
        try {
            String instId = toInstId(symbol);
            Map<String, String> query = new HashMap<>();
            query.put("instType", "SWAP");
            query.put("instId", instId);
            JsonNode root = publicRequest("/api/v5/public/instruments", query);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new ExchangeException(ExchangeException.ErrorCode.INVALID_SYMBOL,
                        "Symbol rules not found for " + symbol);
            }

            JsonNode node = data.get(0);
            BigDecimal tickSize = parsePositiveDecimal(node.path("tickSz").asText(), new BigDecimal("0.1"));
            BigDecimal lotStep = parsePositiveDecimal(node.path("lotSz").asText(), BigDecimal.ONE);
            BigDecimal minContracts = parsePositiveDecimal(node.path("minSz").asText(), lotStep);
            BigDecimal contractValue = parsePositiveDecimal(node.path("ctVal").asText(), BigDecimal.ONE);

            return new SymbolRules(
                    instId,
                    tickSize,
                    lotStep,
                    minContracts,
                    contractValue,
                    System.currentTimeMillis()
            );
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR,
                    "Fetch symbol rules failed: " + symbol, e);
        }
    }

    private BigDecimal normalizeContracts(BigDecimal baseQuantity, SymbolRules rules) throws ExchangeException {
        if (baseQuantity == null || baseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED, "Invalid quantity: " + baseQuantity);
        }
        BigDecimal rawContracts = baseQuantity.divide(rules.contractValue, 16, RoundingMode.DOWN);
        BigDecimal normalized = applyStep(rawContracts, rules.lotStepSize);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Quantity rounded to zero contracts: raw=" + baseQuantity);
        }
        if (normalized.compareTo(rules.minContracts) < 0) {
            throw new ExchangeException(ExchangeException.ErrorCode.ORDER_REJECTED,
                    "Quantity below min contracts: qty=" + normalized + ", min=" + rules.minContracts);
        }
        return normalized.stripTrailingZeros();
    }

    private BigDecimal contractsToBaseQuantity(BigDecimal contracts, SymbolRules rules) {
        return Decimal.scaleQuantity(contracts.multiply(rules.contractValue));
    }

    private BigDecimal resolveMinProtectiveStopNotional(SymbolRules rules, BigDecimal stopPrice) {
        BigDecimal configuredFloor = ConfigManager.getInstance()
                .getBigDecimalProperty("okx.min.notional", new BigDecimal("5"));
        if (configuredFloor == null || configuredFloor.compareTo(BigDecimal.ZERO) < 0) {
            configuredFloor = BigDecimal.ZERO;
        }
        BigDecimal exchangeFloor = rules.minContracts
                .multiply(rules.contractValue)
                .multiply(stopPrice);
        if (exchangeFloor.compareTo(BigDecimal.ZERO) < 0) {
            exchangeFloor = BigDecimal.ZERO;
        }
        return configuredFloor.max(exchangeFloor);
    }

    private BigDecimal fetchMarkPrice(String instId) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instType", "SWAP");
        query.put("instId", instId);
        JsonNode root = publicRequest("/api/v5/public/mark-price", query);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return parseDecimal(data.get(0).path("markPx").asText("0"), BigDecimal.ZERO);
    }

    private BigDecimal applyStep(BigDecimal value, BigDecimal step) {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        BigDecimal units = value.divide(step, 0, RoundingMode.DOWN);
        return units.multiply(step);
    }

    private BigDecimal normalizePrice(BigDecimal rawPrice, BigDecimal tickSize, RoundingMode mode) {
        if (rawPrice == null || rawPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
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

    private String toInstId(Symbol symbol) {
        return symbol.getBase() + "-" + symbol.getQuote() + "-SWAP";
    }

    private String toOkxBar(Interval interval) {
        return switch (interval) {
            case ONE_MINUTE -> "1m";
            case FIVE_MINUTES -> "5m";
            case FIFTEEN_MINUTES -> "15m";
            case ONE_HOUR -> "1H";
        };
    }

    private PositionSide parsePositionSide(String posSide, BigDecimal posContracts) {
        if ("long".equalsIgnoreCase(posSide)) {
            return PositionSide.LONG;
        }
        if ("short".equalsIgnoreCase(posSide)) {
            return PositionSide.SHORT;
        }
        return posContracts.compareTo(BigDecimal.ZERO) >= 0 ? PositionSide.LONG : PositionSide.SHORT;
    }

    private boolean cancelAlgoOrder(String algoId, String instId) throws IOException {
        if (algoId == null || algoId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> body = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("algoId", algoId);
        item.put("instId", instId);
        body.add(item);
        JsonNode root = privateRequest("/api/v5/trade/cancel-algos", "POST", null, body, true);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return false;
        }
        JsonNode row = data.get(0);
        return "0".equals(row.path("sCode").asText("0"));
    }

    private boolean isReduceOnlyAlgo(JsonNode row) {
        String reduceOnlyRaw = row.path("reduceOnly").asText("");
        boolean reduceOnly = "true".equalsIgnoreCase(reduceOnlyRaw)
                || (row.path("reduceOnly").isBoolean() && row.path("reduceOnly").asBoolean(false));
        if (!reduceOnly) {
            return false;
        }
        BigDecimal stopTriggerPrice = parseDecimal(row.path("slTriggerPx").asText("0"), BigDecimal.ZERO);
        return stopTriggerPrice.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isAlgoOrderId(String orderId) {
        return orderId != null && orderId.startsWith(ALGO_ORDER_PREFIX);
    }

    private String toAlgoOrderId(String rawAlgoId) {
        return ALGO_ORDER_PREFIX + rawAlgoId;
    }

    private String unwrapAlgoOrderId(String orderId) {
        if (isAlgoOrderId(orderId)) {
            return orderId.substring(ALGO_ORDER_PREFIX.length());
        }
        return orderId;
    }

    private String trimClientOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        // OKX requires clOrdId to be <= 32 chars and alphanumeric.
        String normalized = orderId.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.isBlank()) {
            normalized = "ord" + Integer.toUnsignedString(orderId.hashCode(), 36);
        }
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    private JsonNode queryOrderNode(String instId, String idField, String idValue) throws IOException {
        if (idValue == null || idValue.isBlank()) {
            return null;
        }
        Map<String, String> query = new HashMap<>();
        query.put("instId", instId);
        query.put(idField, idValue);
        JsonNode root = privateRequest("/api/v5/trade/order", "GET", query, null);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }
        return data.get(0);
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

    private BigDecimal parsePositiveDecimal(String raw, BigDecimal fallback) {
        BigDecimal value = parseDecimal(raw, fallback);
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            return value;
        }
        return fallback;
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private BigDecimal pickFirstPositive(BigDecimal... values) {
        for (BigDecimal v : values) {
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                return v;
            }
        }
        return BigDecimal.ZERO;
    }

    private JsonNode publicRequest(String path, Map<String, String> queryParams) throws IOException {
        String query = buildQueryString(queryParams);
        String requestPath = query.isEmpty() ? path : path + "?" + query;
        Request.Builder requestBuilder = new Request.Builder()
                .url(restBaseUrl + requestPath)
                .get();
        if (demoTradingEnabled) {
            requestBuilder.addHeader("x-simulated-trading", "1");
        }
        Request request = requestBuilder.build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }
            return parseAndValidateOkxResponse(body);
        }
    }

    private JsonNode privateRequest(String path,
                                    String method,
                                    Map<String, String> queryParams,
                                    Object bodyParams) throws IOException {
        return privateRequest(path, method, queryParams, bodyParams, false);
    }

    private JsonNode privateRequest(String path,
                                    String method,
                                    Map<String, String> queryParams,
                                    Object bodyParams,
                                    boolean allowCodeOneWithPerRowStatus) throws IOException {
        String query = buildQueryString(queryParams);
        String requestPath = query.isEmpty() ? path : path + "?" + query;
        String upperMethod = method.toUpperCase(Locale.ROOT);
        String bodyJson = toRequestBodyJson(bodyParams);

        String timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        String preHash = timestamp + upperMethod + requestPath + bodyJson;
        String signature = sign(preHash, secretKey);

        Request.Builder builder = new Request.Builder()
                .url(restBaseUrl + requestPath)
                .addHeader("OK-ACCESS-KEY", apiKey)
                .addHeader("OK-ACCESS-SIGN", signature)
                .addHeader("OK-ACCESS-TIMESTAMP", timestamp)
                .addHeader("OK-ACCESS-PASSPHRASE", passphrase)
                .addHeader("Content-Type", "application/json");
        if (demoTradingEnabled) {
            builder.addHeader("x-simulated-trading", "1");
        }

        if ("POST".equals(upperMethod)) {
            builder.post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE));
        } else if ("DELETE".equals(upperMethod)) {
            builder.delete(RequestBody.create(bodyJson, JSON_MEDIA_TYPE));
        } else {
            builder.get();
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }
            return parseAndValidateOkxResponse(body, allowCodeOneWithPerRowStatus);
        }
    }

    private String toRequestBodyJson(Object bodyParams) throws IOException {
        if (bodyParams == null) {
            return "";
        }
        if (bodyParams instanceof Map<?, ?> map && map.isEmpty()) {
            return "";
        }
        if (bodyParams instanceof List<?> list && list.isEmpty()) {
            return "";
        }
        return objectMapper.writeValueAsString(bodyParams);
    }

    private boolean isRetryableServiceUnavailable(IOException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage();
        return message.contains("HTTP 503")
                || message.contains("\"code\":\"50001\"")
                || message.contains("Service temporarily unavailable");
    }

    private JsonNode parseAndValidateOkxResponse(String body) throws IOException {
        return parseAndValidateOkxResponse(body, false);
    }

    private JsonNode parseAndValidateOkxResponse(String body, boolean allowCodeOneWithPerRowStatus) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String code = root.path("code").asText("");
        if ("0".equals(code)) {
            return root;
        }
        JsonNode data = root.path("data");
        if (allowCodeOneWithPerRowStatus && "1".equals(code) && hasPerRowStatus(data)) {
            return root;
        }
        String msg = root.path("msg").asText("unknown");
        String detail = buildPerRowStatusDetail(data);
        if (detail.isBlank()) {
            throw new IOException("OKX API error: code=" + code + ", msg=" + msg);
        }
        throw new IOException("OKX API error: code=" + code + ", msg=" + msg + ", detail=" + detail);
    }

    private boolean hasPerRowStatus(JsonNode data) {
        if (!data.isArray() || data.isEmpty()) {
            return false;
        }
        JsonNode first = data.get(0);
        return first.has("sCode") || first.has("sMsg");
    }

    private String buildPerRowStatusDetail(JsonNode data) {
        if (!data.isArray() || data.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("; ");
        int count = Math.min(data.size(), 3);
        for (int i = 0; i < count; i++) {
            JsonNode row = data.get(i);
            String sCode = row.path("sCode").asText("");
            String sMsg = row.path("sMsg").asText("");
            String ordId = row.path("ordId").asText("");
            String clOrdId = row.path("clOrdId").asText("");
            StringBuilder part = new StringBuilder();
            if (!sCode.isBlank()) {
                part.append("sCode=").append(sCode);
            }
            if (!sMsg.isBlank()) {
                if (part.length() > 0) {
                    part.append(", ");
                }
                part.append("sMsg=").append(sMsg);
            }
            if (!ordId.isBlank()) {
                if (part.length() > 0) {
                    part.append(", ");
                }
                part.append("ordId=").append(ordId);
            }
            if (!clOrdId.isBlank()) {
                if (part.length() > 0) {
                    part.append(", ");
                }
                part.append("clOrdId=").append(clOrdId);
            }
            if (part.length() > 0) {
                joiner.add(part.toString());
            }
        }
        return joiner.toString();
    }

    private String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        // Keep deterministic order for signing.
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(urlEncode(entry.getKey()))
                    .append("=")
                    .append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Sign failed", e);
        }
    }
}
