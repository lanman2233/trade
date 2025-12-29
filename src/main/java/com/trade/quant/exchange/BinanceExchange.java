package com.trade.quant.exchange;

import com.trade.quant.core.*;
import com.trade.quant.market.MarketDataListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Binance 合约交易所实现
 * USDT 本位合约
 */
public class BinanceExchange implements Exchange {

    private static final String BASE_URL = "https://fapi.binance.com";
    private static final String WS_BASE_URL = "wss://fstream.binance.com/ws";
    private static final String WS_USER_BASE_URL = "wss://fstream.binance.com/ws";

    private String apiKey;
    private String secretKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, WebSocket> webSockets;
    private boolean connected = false;

    public BinanceExchange() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.webSockets = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return "Binance";
    }

    @Override
    public void setApiKey(String apiKey, String secretKey, String passphrase) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        // Binance 不需要 passphrase
    }

    @Override
    public void setProxy(String host, int port) {
        // TODO: 实现代理设置
    }

    @Override
    public AccountInfo getAccountInfo() throws ExchangeException {
        try {
            String endpoint = "/fapi/v2/account";
            String response = signedRequest(endpoint, "GET", null);
            JsonNode json = objectMapper.readTree(response);

            BigDecimal totalWalletBalance = new BigDecimal(json.path("totalWalletBalance").asText());
            BigDecimal availableBalance = new BigDecimal(json.path("availableBalance").asText());
            BigDecimal unrealizedPnl = new BigDecimal(json.path("totalUnrealizedProfit").asText());

            return new AccountInfo(
                    Decimal.scalePrice(totalWalletBalance),
                    Decimal.scalePrice(availableBalance),
                    Decimal.scalePrice(unrealizedPnl),
                    BigDecimal.ZERO // 保证金率需要额外计算
            );

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "获取账户信息失败", e);
        }
    }

    @Override
    public Ticker getTicker(Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v1/ticker/bookTicker?symbol=" + symbol.toPairString();
            String response = publicRequest(endpoint);
            JsonNode json = objectMapper.readTree(response);

            BigDecimal bidPrice = new BigDecimal(json.get("bidPrice").asText());
            BigDecimal askPrice = new BigDecimal(json.get("askPrice").asText());

            // 获取24h统计
            String statsEndpoint = "/fapi/v1/ticker/24hr?symbol=" + symbol.toPairString();
            String statsResponse = publicRequest(statsEndpoint);
            JsonNode statsJson = objectMapper.readTree(statsResponse);

            BigDecimal lastPrice = new BigDecimal(statsJson.get("lastPrice").asText());
            BigDecimal volume24h = new BigDecimal(statsJson.get("volume").asText());
            BigDecimal high24h = new BigDecimal(statsJson.get("highPrice").asText());
            BigDecimal low24h = new BigDecimal(statsJson.get("lowPrice").asText());

            return new Ticker(
                    symbol,
                    Decimal.scalePrice(bidPrice),
                    Decimal.scalePrice(askPrice),
                    Decimal.scalePrice(lastPrice),
                    Decimal.scaleQuantity(volume24h),
                    Decimal.scalePrice(high24h),
                    Decimal.scalePrice(low24h),
                    Instant.now()
            );

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "获取Ticker失败", e);
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
            for (JsonNode node : jsonArray) {
                Instant openTime = Instant.ofEpochMilli(node.get(0).asLong());
                BigDecimal open = new BigDecimal(node.get(1).asText());
                BigDecimal high = new BigDecimal(node.get(2).asText());
                BigDecimal low = new BigDecimal(node.get(3).asText());
                BigDecimal close = new BigDecimal(node.get(4).asText());
                BigDecimal volume = new BigDecimal(node.get(5).asText());
                Instant closeTime = Instant.ofEpochMilli(node.get(6).asLong());
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
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "获取K线数据失败", e);
        }
    }

    @Override
    public String placeOrder(Order order) throws ExchangeException {
        // TODO: 实现下单逻辑
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public boolean cancelOrder(String orderId, Symbol symbol) throws ExchangeException {
        // TODO: 实现撤单逻辑
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public Order getOrder(String orderId, Symbol symbol) throws ExchangeException {
        // TODO: 实现查询订单逻辑
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public List<Position> getOpenPositions(Symbol symbol) throws ExchangeException {
        try {
            String endpoint = "/fapi/v2_positionRisk?symbol=" + symbol.toPairString();
            String response = signedRequest(endpoint, "GET", null);
            JsonNode jsonArray = objectMapper.readTree(response);

            List<Position> positions = new ArrayList<>();
            for (JsonNode node : jsonArray) {
                BigDecimal positionAmt = new BigDecimal(node.get("positionAmt").asText());
                if (positionAmt.compareTo(BigDecimal.ZERO) != 0) {
                    PositionSide side = positionAmt.compareTo(BigDecimal.ZERO) > 0
                            ? PositionSide.LONG : PositionSide.SHORT;

                    BigDecimal entryPrice = new BigDecimal(node.get("entryPrice").asText());
                    BigDecimal unrealizedPnl = new BigDecimal(node.get("unRealizedProfit").asText());
                    BigDecimal leverage = new BigDecimal(node.get("leverage").asText());
                    BigDecimal stopLoss = null; // 需要从订单中获取

                    Position pos = new Position(
                            symbol,
                            side,
                            Decimal.scalePrice(entryPrice),
                            Decimal.scaleQuantity(positionAmt.abs()),
                            stopLoss,
                            leverage
                    );

                    positions.add(pos);
                }
            }

            return positions;

        } catch (IOException e) {
            throw new ExchangeException(ExchangeException.ErrorCode.API_ERROR, "获取持仓信息失败", e);
        }
    }

    @Override
    public List<Trade> getTradeHistory(Symbol symbol, int limit) throws ExchangeException {
        // TODO: 实现获取交易历史
        throw new UnsupportedOperationException("暂未实现");
    }

    @Override
    public void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener) {
        String stream = symbol.toPairString().toLowerCase() + "@kline_" + interval.getCode();
        String wsUrl = WS_BASE_URL + "/" + stream;

        WebSocket ws = httpClient.newWebSocket(createRequest(wsUrl), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonNode json = objectMapper.readTree(text);
                    JsonNode kline = json.get("k");

                    Instant openTime = Instant.ofEpochMilli(kline.get("t").asLong());
                    BigDecimal open = new BigDecimal(kline.get("o").asText());
                    BigDecimal high = new BigDecimal(kline.get("h").asText());
                    BigDecimal low = new BigDecimal(kline.get("l").asText());
                    BigDecimal close = new BigDecimal(kline.get("c").asText());
                    BigDecimal volume = new BigDecimal(kline.get("v").asText());
                    Instant closeTime = Instant.ofEpochMilli(kline.get("T").asLong());

                    KLine kLine = new KLine(symbol, interval, openTime, closeTime,
                            Decimal.scalePrice(open),
                            Decimal.scalePrice(high),
                            Decimal.scalePrice(low),
                            Decimal.scalePrice(close),
                            Decimal.scaleQuantity(volume),
                            BigDecimal.ZERO,
                            0);

                    listener.onKLine(kLine);

                } catch (Exception e) {
                    listener.onError(e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                listener.onError(t);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                connected = false;
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
            }
        });

        webSockets.put(stream, ws);
    }

    @Override
    public void subscribeTicker(Symbol symbol, MarketDataListener listener) {
        String stream = symbol.toPairString().toLowerCase() + "@bookTicker";
        String wsUrl = WS_BASE_URL + "/" + stream;

        WebSocket ws = httpClient.newWebSocket(createRequest(wsUrl), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonNode json = objectMapper.readTree(text);
                    BigDecimal bidPrice = new BigDecimal(json.get("b").asText());
                    BigDecimal askPrice = new BigDecimal(json.get("a").asText());

                    Ticker ticker = new Ticker(
                            symbol,
                            Decimal.scalePrice(bidPrice),
                            Decimal.scalePrice(askPrice),
                            bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            Instant.now()
                    );

                    listener.onTicker(ticker);

                } catch (Exception e) {
                    listener.onError(e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                listener.onError(t);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                connected = false;
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
            }
        });

        webSockets.put(stream, ws);
    }

    @Override
    public void unsubscribeKLine(Symbol symbol, Interval interval) {
        String stream = symbol.toPairString().toLowerCase() + "@kline_" + interval.getCode();
        WebSocket ws = webSockets.remove(stream);
        if (ws != null) {
            ws.close(1000, "Unsubscribe");
        }
    }

    @Override
    public void unsubscribeTicker(Symbol symbol) {
        String stream = symbol.toPairString().toLowerCase() + "@bookTicker";
        WebSocket ws = webSockets.remove(stream);
        if (ws != null) {
            ws.close(1000, "Unsubscribe");
        }
    }

    @Override
    public void connect() throws ExchangeException {
        connected = true;
    }

    @Override
    public void disconnect() {
        webSockets.values().forEach(ws -> ws.close(1000, "Disconnect"));
        webSockets.clear();
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ==================== 私有方法 ====================

    private Request createRequest(String url) {
        return new Request.Builder()
                .url(url)
                .build();
    }

    private String publicRequest(String endpoint) throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private String signedRequest(String endpoint, String method, String requestBody) throws IOException {
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp;

        // 签名
        String signature = HmacSHA256(queryString, secretKey);

        String url = BASE_URL + endpoint + (endpoint.contains("?") ? "&" : "?") + queryString + "&signature=" + signature;

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey);

        if ("POST".equals(method)) {
            requestBuilder.post(RequestBody.create(requestBody == null ? "" : requestBody, null));
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.body().string());
            }
            return response.body().string();
        }
    }

    private String HmacSHA256(String data, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
