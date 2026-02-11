package com.trade.quant.execution;

import com.trade.quant.backtest.BacktestTradeListener;
import com.trade.quant.backtest.ClosedTrade;
import com.trade.quant.core.*;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ExchangeException;
import com.trade.quant.exchange.ProtectiveStopCapableExchange;
import com.trade.quant.execution.notifier.NetworkAlertNotifier;
import com.trade.quant.execution.notifier.NoopNetworkAlertNotifier;
import com.trade.quant.execution.notifier.NoopTradeFillNotifier;
import com.trade.quant.execution.notifier.TradeFillNotifier;
import com.trade.quant.market.MarketDataListener;
import com.trade.quant.market.MarketDataManager;
import com.trade.quant.risk.RiskConfig;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.SignalType;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.StrategyEngine;
import com.trade.quant.strategy.TradeMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingEngineLiveEntryPricingTest {

    private static final Symbol SYMBOL = Symbol.of("BTC-USDT");

    private String originalLiveEntryRepriceEnabled;

    @BeforeEach
    void setUp() throws Exception {
        originalLiveEntryRepriceEnabled = overrideConfig("live.entry.reprice.enabled", "true");
    }

    @AfterEach
    void tearDown() throws Exception {
        restoreConfig("live.entry.reprice.enabled", originalLiveEntryRepriceEnabled);
    }

    @Test
    void onSignal_shouldRepriceBuyEntryBeforeRiskControl() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedBuyOrder(new BigDecimal("102"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        TradingEngine engine = buildEngine(exchange, riskControl);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        assertNotNull(riskControl.lastSignal);
        assertEquals(0, riskControl.lastSignal.getPrice().compareTo(new BigDecimal("101.00000000")));
        assertEquals(0, riskControl.lastSignal.getStopLoss().compareTo(new BigDecimal("91.00000000")));
    }

    @Test
    void onSignal_shouldFallbackToOriginalSignalWhenTickerUnavailable() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.throwTickerException = true;
        exchange.executedOrder = executedBuyOrder(new BigDecimal("100"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        TradingEngine engine = buildEngine(exchange, riskControl);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        assertNotNull(riskControl.lastSignal);
        assertEquals(0, riskControl.lastSignal.getPrice().compareTo(new BigDecimal("100")));
        assertEquals(0, riskControl.lastSignal.getStopLoss().compareTo(new BigDecimal("90")));
    }

    @Test
    void onSignal_shouldNotifyNetworkAlertWhenTickerUnavailable() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.throwTickerException = true;
        exchange.executedOrder = executedBuyOrder(new BigDecimal("100"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        CapturingNetworkAlertNotifier notifier = new CapturingNetworkAlertNotifier();
        TradingEngine engine = buildEngine(exchange, riskControl, null, notifier);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        assertEquals(1, notifier.notifyCount);
        assertEquals("entry_reprice_ticker", notifier.lastScene);
    }

    @Test
    void onSignal_shouldNotifyTradeFillOnConfirmedEntryAndExit() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedOrder(Side.BUY, new BigDecimal("103"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        CapturingTradeFillNotifier fillNotifier = new CapturingTradeFillNotifier();
        TradingEngine engine = buildEngine(
                exchange,
                riskControl,
                null,
                NoopNetworkAlertNotifier.INSTANCE,
                fillNotifier
        );
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));
        assertEquals(1, fillNotifier.entryCount);
        assertEquals(0, fillNotifier.lastEntryPrice.compareTo(new BigDecimal("103")));
        assertEquals(0, fillNotifier.lastEntryQuantity.compareTo(new BigDecimal("1")));

        exchange.executedOrder = executedOrder(Side.SELL, new BigDecimal("104"), new BigDecimal("1"));
        invokeOnSignal(engine, exitLongSignal(new BigDecimal("104"), new BigDecimal("1")));

        assertEquals(1, fillNotifier.exitCount);
        assertEquals(0, fillNotifier.lastExitPrice.compareTo(new BigDecimal("104")));
        assertEquals(0, fillNotifier.lastExitQuantity.compareTo(new BigDecimal("1")));
        assertEquals(0, fillNotifier.lastExitPnl.compareTo(new BigDecimal("1")));
    }

    @Test
    void onSignal_shouldNotNotifyTradeFillWhenOrderNotFilled() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedOrder(Side.BUY, BigDecimal.ZERO, BigDecimal.ZERO);
        exchange.openPositions = Collections.emptyList();

        CapturingRiskControl riskControl = new CapturingRiskControl();
        CapturingTradeFillNotifier fillNotifier = new CapturingTradeFillNotifier();
        TradingEngine engine = buildEngine(
                exchange,
                riskControl,
                null,
                NoopNetworkAlertNotifier.INSTANCE,
                fillNotifier
        );
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        assertEquals(0, fillNotifier.entryCount);
        assertEquals(0, fillNotifier.exitCount);
    }

    @Test
    void onSignal_shouldRecomputeInitialStopFromActualFillPrice() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedBuyOrder(new BigDecimal("103"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        TradingEngine engine = buildEngine(exchange, riskControl);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        Position tracked = readTrackedPosition(engine, SYMBOL);
        assertNotNull(tracked);
        assertEquals(0, tracked.getEntryPrice().compareTo(new BigDecimal("103")));
        assertEquals(0, tracked.getStopLoss().compareTo(new BigDecimal("93.00000000")));
        assertTrue(tracked.getQuantity().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void onSignal_shouldNotRepriceWhenFeatureDisabled() throws Exception {
        restoreConfig("live.entry.reprice.enabled", "false");

        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedBuyOrder(new BigDecimal("102"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        TradingEngine engine = buildEngine(exchange, riskControl);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));

        assertNotNull(riskControl.lastSignal);
        assertEquals(0, riskControl.lastSignal.getPrice().compareTo(new BigDecimal("100")));
        assertEquals(0, riskControl.lastSignal.getStopLoss().compareTo(new BigDecimal("90")));
    }

    @Test
    void onSignal_shouldNotifyStrategyLifecycleCallbacks() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedOrder(Side.BUY, new BigDecimal("103"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        RecordingTradeStrategy strategy = new RecordingTradeStrategy();
        TradingEngine engine = buildEngine(exchange, riskControl, strategy);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));
        assertEquals(1, strategy.openedCount);

        exchange.executedOrder = executedOrder(Side.SELL, new BigDecimal("104"), new BigDecimal("1"));
        invokeOnSignal(engine, exitLongSignal(new BigDecimal("104"), new BigDecimal("1")));

        assertEquals(1, strategy.closedCount);
        assertEquals(ExitReason.STRATEGY_EXIT, strategy.lastExitReason);
    }

    @Test
    void updatePositions_shouldAdoptPendingEntryWithStopDistance() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedOrder(Side.BUY, BigDecimal.ZERO, BigDecimal.ZERO);
        exchange.openPositions = Collections.emptyList();

        CapturingRiskControl riskControl = new CapturingRiskControl();
        RecordingTradeStrategy strategy = new RecordingTradeStrategy();
        TradingEngine engine = buildEngine(exchange, riskControl, strategy);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));
        assertEquals(null, readTrackedPosition(engine, SYMBOL));

        Position exchangePosition = new Position(
                SYMBOL,
                PositionSide.LONG,
                new BigDecimal("120"),
                new BigDecimal("1"),
                null,
                BigDecimal.ONE
        );
        exchange.openPositions = List.of(exchangePosition);
        engine.updatePositions();

        Position adopted = readTrackedPosition(engine, SYMBOL);
        assertNotNull(adopted);
        assertEquals(0, adopted.getEntryPrice().compareTo(new BigDecimal("120")));
        assertEquals(0, adopted.getStopLoss().compareTo(new BigDecimal("110.00000000")));
    }

    @Test
    void updatePositions_shouldRefreshExchangeStopWhenTrailingStopChanges() throws Exception {
        FakeProtectiveExchange exchange = new FakeProtectiveExchange();
        exchange.ticker = ticker(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"));
        exchange.executedOrder = executedOrder(Side.BUY, new BigDecimal("100"), new BigDecimal("1"));

        CapturingRiskControl riskControl = new CapturingRiskControl();
        TradingEngine engine = buildEngine(exchange, riskControl);
        setEngineRunning(engine, true);

        invokeOnSignal(engine, entryLongSignal(new BigDecimal("100"), new BigDecimal("90")));
        assertEquals(1, exchange.placedStopPrices.size());
        assertEquals(0, exchange.placedStopPrices.get(0).compareTo(new BigDecimal("90.00000000")));

        Position tracked = readTrackedPosition(engine, SYMBOL);
        assertNotNull(tracked);
        tracked.updateStopLoss(new BigDecimal("95"));

        Position exchangePosition = new Position(
                SYMBOL,
                PositionSide.LONG,
                tracked.getEntryPrice(),
                tracked.getQuantity(),
                null,
                BigDecimal.ONE
        );
        exchange.openPositions = List.of(exchangePosition);
        engine.updatePositions();

        assertEquals(2, exchange.placedStopPrices.size());
        assertEquals(0, exchange.placedStopPrices.get(1).compareTo(new BigDecimal("95.00000000")));
        assertTrue(exchange.cancelCalledCount > 0);
    }

    @Test
    void closePosition_shouldNotifyStrategyWithStopLossReason() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.executedOrder = executedOrder(Side.SELL, new BigDecimal("95"), new BigDecimal("1"));
        exchange.openPositions = Collections.emptyList();

        CapturingRiskControl riskControl = new CapturingRiskControl();
        RecordingTradeStrategy strategy = new RecordingTradeStrategy();
        TradingEngine engine = buildEngine(exchange, riskControl, strategy);
        setEngineRunning(engine, true);

        Position tracked = new Position(
                SYMBOL,
                PositionSide.LONG,
                new BigDecimal("100"),
                new BigDecimal("1"),
                new BigDecimal("90"),
                BigDecimal.ONE
        );
        setTrackedPosition(engine, tracked, strategy.getStrategyId());

        invokeClosePosition(engine, tracked, new BigDecimal("95"));

        assertEquals(1, strategy.closedCount);
        assertEquals(ExitReason.STOP_LOSS, strategy.lastExitReason);
    }

    private TradingEngine buildEngine(FakeExchange exchange, CapturingRiskControl riskControl) {
        return buildEngine(
                exchange,
                riskControl,
                null,
                NoopNetworkAlertNotifier.INSTANCE,
                NoopTradeFillNotifier.INSTANCE
        );
    }

    private TradingEngine buildEngine(FakeExchange exchange, CapturingRiskControl riskControl, RecordingTradeStrategy strategy) {
        return buildEngine(
                exchange,
                riskControl,
                strategy,
                NoopNetworkAlertNotifier.INSTANCE,
                NoopTradeFillNotifier.INSTANCE
        );
    }

    private TradingEngine buildEngine(FakeExchange exchange,
                                      CapturingRiskControl riskControl,
                                      RecordingTradeStrategy strategy,
                                      NetworkAlertNotifier notifier) {
        return buildEngine(
                exchange,
                riskControl,
                strategy,
                notifier,
                NoopTradeFillNotifier.INSTANCE
        );
    }

    private TradingEngine buildEngine(FakeExchange exchange,
                                      CapturingRiskControl riskControl,
                                      RecordingTradeStrategy strategy,
                                      NetworkAlertNotifier networkNotifier,
                                      TradeFillNotifier tradeFillNotifier) {
        MarketDataManager marketDataManager = new MarketDataManager(exchange);
        StrategyEngine strategyEngine = new StrategyEngine(marketDataManager);
        if (strategy != null) {
            strategyEngine.addStrategy(strategy);
        }
        TestOrderExecutor orderExecutor = new TestOrderExecutor(exchange);
        StopLossManager stopLossManager = new StopLossManager(exchange);
        return new TradingEngine(
                strategyEngine,
                riskControl,
                orderExecutor,
                stopLossManager,
                exchange,
                networkNotifier,
                tradeFillNotifier
        );
    }

    private static Signal entryLongSignal(BigDecimal price, BigDecimal stopLoss) {
        return new Signal(
                "BTC-DONCHIAN48-BREAKOUT",
                SYMBOL,
                SignalType.ENTRY_LONG,
                Side.BUY,
                price,
                BigDecimal.ZERO,
                stopLoss,
                BigDecimal.ZERO,
                "test"
        );
    }

    private static Ticker ticker(BigDecimal bid, BigDecimal ask, BigDecimal last) {
        return new Ticker(
                SYMBOL,
                bid,
                ask,
                last,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now()
        );
    }

    private static Order executedBuyOrder(BigDecimal avgFillPrice, BigDecimal filledQty) {
        return executedOrder(Side.BUY, avgFillPrice, filledQty);
    }

    private static Order executedOrder(Side side, BigDecimal avgFillPrice, BigDecimal filledQty) {
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .symbol(SYMBOL)
                .side(side)
                .type(OrderType.MARKET)
                .quantity(new BigDecimal("1"))
                .price(BigDecimal.ZERO)
                .stopLoss(BigDecimal.ZERO)
                .takeProfit(BigDecimal.ZERO)
                .strategyId("TEST")
                .reduceOnly(false)
                .build();
        if (avgFillPrice != null) {
            order.setAvgFillPrice(avgFillPrice);
        }
        if (filledQty != null) {
            order.setFilledQuantity(filledQty);
        }
        return order;
    }

    private static Signal exitLongSignal(BigDecimal price, BigDecimal qty) {
        return new Signal(
                "BTC-DONCHIAN48-BREAKOUT",
                SYMBOL,
                SignalType.EXIT_LONG,
                Side.SELL,
                price,
                qty,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "test_exit"
        );
    }

    private static void setEngineRunning(TradingEngine engine, boolean running) throws Exception {
        Field runningField = TradingEngine.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(engine, running);
    }

    private static void invokeOnSignal(TradingEngine engine, Signal signal) throws Exception {
        Method onSignal = TradingEngine.class.getDeclaredMethod("onSignal", Signal.class);
        onSignal.setAccessible(true);
        onSignal.invoke(engine, signal);
    }

    private static void invokeClosePosition(TradingEngine engine, Position position, BigDecimal price) throws Exception {
        Method closePosition = TradingEngine.class.getDeclaredMethod("closePosition", Position.class, BigDecimal.class);
        closePosition.setAccessible(true);
        closePosition.invoke(engine, position, price);
    }

    @SuppressWarnings("unchecked")
    private static Position readTrackedPosition(TradingEngine engine, Symbol symbol) throws Exception {
        Field trackedPositionsField = TradingEngine.class.getDeclaredField("trackedPositions");
        trackedPositionsField.setAccessible(true);
        Map<String, Position> trackedPositions = (Map<String, Position>) trackedPositionsField.get(engine);
        return trackedPositions.get(symbol.toPairString());
    }

    @SuppressWarnings("unchecked")
    private static void setTrackedPosition(TradingEngine engine, Position position, String strategyId) throws Exception {
        Field trackedPositionsField = TradingEngine.class.getDeclaredField("trackedPositions");
        trackedPositionsField.setAccessible(true);
        Map<String, Position> trackedPositions = (Map<String, Position>) trackedPositionsField.get(engine);
        trackedPositions.put(position.getSymbol().toPairString(), position);

        Field strategyIdsField = TradingEngine.class.getDeclaredField("trackedPositionStrategyIds");
        strategyIdsField.setAccessible(true);
        Map<String, String> trackedStrategyIds = (Map<String, String>) strategyIdsField.get(engine);
        trackedStrategyIds.put(position.getSymbol().toPairString(), strategyId);
    }

    private static String overrideConfig(String key, String value) throws Exception {
        ConfigManager configManager = ConfigManager.getInstance();
        Field propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(configManager);
        String oldValue = properties.getProperty(key);
        properties.setProperty(key, value);
        return oldValue;
    }

    private static void restoreConfig(String key, String original) throws Exception {
        ConfigManager configManager = ConfigManager.getInstance();
        Field propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(configManager);
        if (original == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, original);
        }
    }

    private static final class CapturingRiskControl extends RiskControl {
        private Signal lastSignal;

        private CapturingRiskControl() {
            super(
                    RiskConfig.builder()
                            .riskPerTrade(new BigDecimal("0.01"))
                            .maxStopLossPercent(new BigDecimal("10"))
                            .build(),
                    new AccountInfo(new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO)
            );
        }

        @Override
        public Order validateAndCreateOrder(Signal signal, List<Position> existingPositions) {
            this.lastSignal = signal;
            return Order.builder()
                    .orderId(UUID.randomUUID().toString())
                    .symbol(signal.getSymbol())
                    .side(signal.getSide())
                    .type(OrderType.MARKET)
                    .quantity(new BigDecimal("1"))
                    .price(BigDecimal.ZERO)
                    .stopLoss(signal.getStopLoss() == null ? BigDecimal.ZERO : signal.getStopLoss())
                    .takeProfit(signal.getTakeProfit() == null ? BigDecimal.ZERO : signal.getTakeProfit())
                    .strategyId(signal.getStrategyId())
                    .reduceOnly(false)
                    .build();
        }
    }

    private static final class TestOrderExecutor extends OrderExecutor {
        private TestOrderExecutor(Exchange exchange) {
            super(exchange, new InMemoryPersistence());
        }

        @Override
        public String submitOrder(Order order) {
            return "order-test-001";
        }
    }

    private static final class InMemoryPersistence implements Persistence {
        @Override
        public void saveOrder(Order order) {
        }

        @Override
        public void updateOrder(Order order) {
        }

        @Override
        public List<Order> loadPendingOrders() {
            return Collections.emptyList();
        }

        @Override
        public void deleteOrder(String orderId) {
        }
    }

    private static class FakeExchange implements Exchange {
        protected boolean throwTickerException;
        protected Ticker ticker = ticker(new BigDecimal("99"), new BigDecimal("100"), new BigDecimal("99.5"));
        protected Order executedOrder = executedBuyOrder(new BigDecimal("100"), new BigDecimal("1"));
        protected List<Position> openPositions = Collections.emptyList();

        @Override
        public String getName() {
            return "FAKE";
        }

        @Override
        public AccountInfo getAccountInfo() {
            return new AccountInfo(new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Override
        public Ticker getTicker(Symbol symbol) throws ExchangeException {
            if (throwTickerException) {
                throw new ExchangeException(ExchangeException.ErrorCode.NETWORK_ERROR, "ticker unavailable");
            }
            return ticker;
        }

        @Override
        public List<KLine> getKLines(Symbol symbol, Interval interval, int limit, Long endTime) {
            return Collections.emptyList();
        }

        @Override
        public String placeOrder(Order order) {
            return "exchange-order-id";
        }

        @Override
        public boolean cancelOrder(String orderId, Symbol symbol) {
            return true;
        }

        @Override
        public Order getOrder(String orderId, Symbol symbol) {
            return executedOrder;
        }

        @Override
        public List<Position> getOpenPositions(Symbol symbol) {
            return openPositions;
        }

        @Override
        public List<Trade> getTradeHistory(Symbol symbol, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void subscribeKLine(Symbol symbol, Interval interval, MarketDataListener listener) {
        }

        @Override
        public void subscribeTicker(Symbol symbol, MarketDataListener listener) {
        }

        @Override
        public void unsubscribeKLine(Symbol symbol, Interval interval) {
        }

        @Override
        public void unsubscribeTicker(Symbol symbol) {
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void setApiKey(String apiKey, String secretKey, String passphrase) {
        }

        @Override
        public void setProxy(String host, int port) {
        }
    }

    private static final class FakeProtectiveExchange extends FakeExchange implements ProtectiveStopCapableExchange {
        private final List<BigDecimal> placedStopPrices = new ArrayList<>();
        private int cancelCalledCount;

        @Override
        public BigDecimal normalizeMarketQuantity(Symbol symbol, BigDecimal rawQuantity) {
            return rawQuantity;
        }

        @Override
        public BigDecimal normalizeStopPrice(Symbol symbol, Side closeSide, BigDecimal rawStopPrice) {
            return Decimal.scalePrice(rawStopPrice);
        }

        @Override
        public String placeReduceOnlyStopMarketOrder(Symbol symbol, Side side, BigDecimal stopPrice, BigDecimal quantity, String clientOrderId) {
            placedStopPrices.add(Decimal.scalePrice(stopPrice));
            return "stop-" + placedStopPrices.size();
        }

        @Override
        public int cancelReduceOnlyStopOrders(Symbol symbol) {
            return 0;
        }

        @Override
        public boolean cancelOrder(String orderId, Symbol symbol) {
            cancelCalledCount++;
            return true;
        }
    }

    private static final class RecordingTradeStrategy extends AbstractStrategy implements BacktestTradeListener {
        private int openedCount;
        private int closedCount;
        private ExitReason lastExitReason;

        private RecordingTradeStrategy() {
            super("TEST-RECORDING", SYMBOL, Interval.FIFTEEN_MINUTES, StrategyConfig.builder().build());
        }

        @Override
        public String getName() {
            return "Test Recording Strategy";
        }

        @Override
        public Signal analyze(List<KLine> kLines) {
            return null;
        }

        @Override
        public void onPositionOpened(Position position, Signal signal, KLine kLine, TradeMetrics metrics) {
            openedCount++;
        }

        @Override
        public void onPositionClosed(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
            closedCount++;
            lastExitReason = reason;
        }
    }

    private static final class CapturingNetworkAlertNotifier implements NetworkAlertNotifier {
        private int notifyCount;
        private String lastScene;

        @Override
        public void notifyExchangeUnavailable(String scene, Throwable error) {
            notifyCount++;
            lastScene = scene;
        }
    }

    private static final class CapturingTradeFillNotifier implements TradeFillNotifier {
        private int entryCount;
        private int exitCount;
        private BigDecimal lastEntryPrice = BigDecimal.ZERO;
        private BigDecimal lastEntryQuantity = BigDecimal.ZERO;
        private BigDecimal lastExitPrice = BigDecimal.ZERO;
        private BigDecimal lastExitQuantity = BigDecimal.ZERO;
        private BigDecimal lastExitPnl = BigDecimal.ZERO;

        @Override
        public void notifyEntryFilled(String fillEventId,
                                      String strategyId,
                                      Symbol symbol,
                                      Side side,
                                      BigDecimal avgFillPrice,
                                      BigDecimal filledQuantity) {
            entryCount++;
            lastEntryPrice = avgFillPrice;
            lastEntryQuantity = filledQuantity;
        }

        @Override
        public void notifyExitFilled(String fillEventId,
                                     String strategyId,
                                     Symbol symbol,
                                     Side side,
                                     BigDecimal avgFillPrice,
                                     BigDecimal filledQuantity,
                                     BigDecimal pnl) {
            exitCount++;
            lastExitPrice = avgFillPrice;
            lastExitQuantity = filledQuantity;
            lastExitPnl = pnl;
        }
    }
}
