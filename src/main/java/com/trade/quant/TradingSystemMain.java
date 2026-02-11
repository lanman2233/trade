package com.trade.quant;

import com.trade.quant.backtest.BacktestConfig;
import com.trade.quant.backtest.BacktestEngine;
import com.trade.quant.backtest.BacktestResult;
import com.trade.quant.backtest.BacktestTradeLogger;
import com.trade.quant.backtest.CsvKLineLoader;
import com.trade.quant.core.*;
import com.trade.quant.exchange.BinanceExchange;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ExchangeFactory;
import com.trade.quant.exchange.OkxExchange;
import com.trade.quant.execution.FilePersistence;
import com.trade.quant.execution.notifier.NetworkAlertNotifier;
import com.trade.quant.execution.notifier.NetworkAlertNotifierFactory;
import com.trade.quant.execution.notifier.NoopNetworkAlertNotifier;
import com.trade.quant.execution.OrderExecutor;
import com.trade.quant.execution.Persistence;
import com.trade.quant.execution.TradingEngine;
import com.trade.quant.market.MarketDataManager;
import com.trade.quant.risk.RiskConfig;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.StrategyEngine;
import com.trade.quant.strategy.impl.BtcDonchian48BreakoutStrategy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 量化交易系统主入口。
 */
public class TradingSystemMain {

    public static void main(String[] args) {
        System.out.println("""
            ================================================
               量化交易系统 v1.0
               风控优先 · 稳定运行
            ================================================
            """);

        String mode = args.length > 0 ? args[0] : ConfigManager.getInstance().getProperty("app.mode", "backtest");
        if ("live".equalsIgnoreCase(mode)) {
            runLive();
        } else if ("backtest".equalsIgnoreCase(mode)) {
            runBacktest();
        } else {
            printUsage();
        }
    }

    /**
     * 运行回测。
     */
    private static void runBacktest() {
        System.out.println("启动回测模式...\n");

        try {
            ConfigManager configManager = ConfigManager.getInstance();

            Symbol symbol = Symbol.of("BTC-USDT");
            String intervalCode = configManager.getProperty("backtest.interval", Interval.FIFTEEN_MINUTES.getCode());
            Interval interval = Interval.fromCode(intervalCode);

            BacktestConfig config = BacktestConfig.builder()
                    .symbol(symbol)
                    .interval(interval)
                    .startTime(Instant.parse(configManager.getProperty("backtest.start.time")))
                    .endTime(Instant.parse(configManager.getProperty("backtest.end.time")))
                    .initialCapital(new BigDecimal(configManager.getProperty("backtest.initial.capital")))
                    .makerFee(new BigDecimal(configManager.getProperty("backtest.maker.fee")))
                    .takerFee(new BigDecimal(configManager.getProperty("backtest.taker.fee")))
                    .slippage(new BigDecimal(configManager.getProperty("backtest.slippage")))
                    .spread(configManager.getBigDecimalProperty("backtest.spread", BigDecimal.ZERO))
                    .limitOrderMaxBars(configManager.getIntProperty("backtest.limit.order.max.bars", 3))
                    .leverage(new BigDecimal(configManager.getProperty("backtest.leverage")))
                    .build();

            String exchangeName = configManager.getProperty("backtest.exchange", "binance");
            Exchange exchange = createAndConfigureExchange(configManager, exchangeName);

            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(new BigDecimal(configManager.getProperty("risk.per.trade")))
                    .cooldownBars(configManager.getIntProperty("strategy.cooldown.bars", 3))
                    .useATRStopLoss(configManager.getBooleanProperty("strategy.use.atr.stoploss", true))
                    .atrStopLossMultiplier(new BigDecimal("0.8"))
                    .build();

            BtcDonchian48BreakoutStrategy strategy = new BtcDonchian48BreakoutStrategy(
                    symbol, interval, strategyConfig, false
            );

            System.out.println("使用策略: " + strategy.getName());
            System.out.println("交易所: " + exchange.getName());
            System.out.println("交易对: " + symbol.toPairString());
            System.out.println("周期: " + interval.getCode());
            System.out.println();

            BacktestTradeLogger tradeLogger = new BacktestTradeLogger("logs/backtest-trades.csv");
            BacktestEngine engine = new BacktestEngine(
                    config,
                    exchange,
                    strategy,
                    tradeLogger,
                    loadLocalKLinesIfPresent(configManager, symbol, interval)
            );
            BacktestResult result = engine.run();

            System.out.println(result);

        } catch (Exception e) {
            System.err.println("回测失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 运行实盘。
     */
    private static void runLive() {
        System.out.println("启动实盘模式...\n");

        NetworkAlertNotifier networkAlertNotifier = NoopNetworkAlertNotifier.INSTANCE;
        try {
            ConfigManager configManager = ConfigManager.getInstance();

            String exchangeName = configManager.getProperty("live.exchange", "binance");
            networkAlertNotifier = NetworkAlertNotifierFactory.fromConfig(configManager, exchangeName);
            Symbol symbol = Symbol.of(configManager.getProperty("live.symbol", "BTC-USDT"));
            String intervalCode = configManager.getProperty("live.interval", Interval.FIFTEEN_MINUTES.getCode());
            Interval interval = Interval.fromCode(intervalCode);
            int historyCount = configManager.getIntProperty("live.history.count", 200);
            if (historyCount < 120) {
                historyCount = 120;
            }

            Exchange exchange = createAndConfigureExchange(configManager, exchangeName);
            System.out.println("实盘交易所: " + exchange.getName());
            if (exchange instanceof BinanceExchange binance && binance.isTestnetEnabled()) {
                System.out.println("当前运行在 Binance 模拟盘（Testnet）环境。");
            } else if (exchange instanceof OkxExchange okx && okx.isDemoTradingEnabled()) {
                System.out.println("当前运行在 OKX 模拟盘（Demo Trading）环境。");
            }

            if (exchange instanceof BinanceExchange binance) {
                try {
                    boolean dualSideMode = binance.isDualSidePositionEnabled();
                    if (dualSideMode) {
                        System.err.println("检测到 Binance 为双向持仓（Hedge）模式，当前系统仅支持单向持仓（One-way）模式。");
                        System.err.println("请先切换为 One-way 模式后再启动实盘。");
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("读取 Binance 持仓模式失败，拒绝启动实盘以避免风险: " + e.getMessage());
                    return;
                }

                int leverage = configManager.getIntProperty("live.leverage", 1);
                String marginType = configManager.getProperty("live.margin.type", "").trim();
                if (leverage > 0) {
                    try {
                        binance.setLeverage(symbol, leverage);
                        System.out.println("设置杠杆: " + leverage);
                    } catch (Exception e) {
                        System.out.println("设置杠杆失败: " + e.getMessage());
                    }
                }
                if (!marginType.isEmpty()) {
                    try {
                        binance.setMarginType(symbol, marginType.toUpperCase());
                        System.out.println("设置保证金模式: " + marginType.toUpperCase());
                    } catch (Exception e) {
                        System.out.println("设置保证金模式失败: " + e.getMessage());
                    }
                }
            } else if (exchange instanceof OkxExchange okx) {
                try {
                    boolean longShortMode = okx.isLongShortModeEnabled();
                    if (longShortMode) {
                        System.err.println("检测到 OKX 为双向持仓（long_short_mode），当前系统仅支持单向持仓（net_mode）。");
                        System.err.println("请先切换为 net_mode 后再启动实盘。");
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("读取 OKX 持仓模式失败，拒绝启动实盘以避免风险: " + e.getMessage());
                    return;
                }

                int leverage = configManager.getIntProperty("live.leverage", 1);
                if (leverage > 0) {
                    try {
                        okx.setLeverage(symbol, leverage);
                        System.out.println("设置杠杆: " + leverage);
                    } catch (Exception e) {
                        System.out.println("设置杠杆失败: " + e.getMessage());
                    }
                }
            }

            List<Position> openPositions = exchange.getOpenPositions(symbol);
            boolean allowExistingPosition = configManager.getBooleanProperty("live.start.allow.existing.position", true);
            if (!openPositions.isEmpty()) {
                if (!allowExistingPosition) {
                    System.err.println("检测到已有持仓，请先手动平仓后再启动实盘。");
                    return;
                }
                System.out.println("检测到已有持仓，系统将继续启动，并自动接管仓位并补齐止损保护。");
            }

            MarketDataManager marketDataManager = new MarketDataManager(exchange);
            marketDataManager.initializeHistoricalData(symbol, interval, historyCount);

            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(new BigDecimal(configManager.getProperty("risk.per.trade")))
                    .cooldownBars(configManager.getIntProperty("strategy.cooldown.bars", 3))
                    .useATRStopLoss(configManager.getBooleanProperty("strategy.use.atr.stoploss", true))
                    .build();

            BtcDonchian48BreakoutStrategy strategy = new BtcDonchian48BreakoutStrategy(symbol, interval, strategyConfig, true);

            StrategyEngine strategyEngine = new StrategyEngine(marketDataManager);
            strategyEngine.addStrategy(strategy);

            BigDecimal riskPerTrade = configManager.getBigDecimalProperty("risk.per.trade", new BigDecimal("0.01"));
            BigDecimal maxDrawdownRatio = configManager.getBigDecimalProperty("risk.max.drawdown", new BigDecimal("0.30"));
            BigDecimal maxPositionRatio = configManager.getBigDecimalProperty("risk.max.position.ratio", BigDecimal.ONE);
            BigDecimal maxStopLossPercent = configManager.getBigDecimalProperty("risk.max.stop.loss.percent", new BigDecimal("50"));
            BigDecimal marginBuffer = configManager.getBigDecimalProperty("risk.margin.buffer", new BigDecimal("1.2"));
            int maxConsecutiveLosses = configManager.getIntProperty("risk.max.consecutive.losses", 3);
            int maxPositionsPerSymbol = configManager.getIntProperty("risk.max.positions.per.symbol", 1);
            BigDecimal leverage = configManager.getBigDecimalProperty("live.leverage", BigDecimal.ONE);

            RiskConfig riskConfig = RiskConfig.builder()
                    .riskPerTrade(riskPerTrade)
                    .maxDrawdownPercent(maxDrawdownRatio.multiply(BigDecimal.valueOf(100)))
                    .maxConsecutiveLosses(maxConsecutiveLosses)
                    .maxPositionRatio(maxPositionRatio)
                    .maxStopLossPercent(maxStopLossPercent)
                    .defaultOrderType(OrderType.MARKET)
                    .leverage(leverage)
                    .build();
            riskConfig.setMarginBuffer(marginBuffer);
            riskConfig.setMaxPositionsPerSymbol(maxPositionsPerSymbol);

            AccountInfo accountInfo = exchange.getAccountInfo();
            RiskControl riskControl = new RiskControl(riskConfig, accountInfo);

            String orderDataDir = "data/orders/" + sanitizeDataDirSegment(exchange.getName());
            Persistence persistence = new FilePersistence(orderDataDir);
            OrderExecutor orderExecutor = new OrderExecutor(exchange, persistence);
            StopLossManager stopLossManager = new StopLossManager(exchange);

            TradingEngine tradingEngine = new TradingEngine(
                    strategyEngine,
                    riskControl,
                    orderExecutor,
                    stopLossManager,
                    exchange,
                    networkAlertNotifier
            );

            tradingEngine.start();

            System.out.println("实盘交易已启动，按 Ctrl+C 退出...");
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("实盘启动失败: " + e.getMessage());
            e.printStackTrace();
            networkAlertNotifier.notifyExchangeUnavailable("runLive.startup", e);
        }
    }

    private static Exchange createAndConfigureExchange(ConfigManager configManager, String exchangeName) {
        Exchange exchange = ExchangeFactory.createExchange(exchangeName);
        if ("okx".equalsIgnoreCase(exchangeName)) {
            exchange.setApiKey(
                    configManager.getOkxApiKey(),
                    configManager.getOkxApiSecret(),
                    configManager.getOkxApiPassphrase()
            );
            if (exchange instanceof OkxExchange okx) {
                boolean demoEnabled = configManager.getBooleanProperty("okx.demo.trading.enabled", false);
                String demoRestBaseUrl = demoEnabled
                        ? configManager.getProperty("okx.demo.rest.base.url", "")
                        : "";
                String demoWsPublicUrl = demoEnabled
                        ? configManager.getProperty("okx.demo.ws.public.url", "")
                        : "";
                String demoWsBusinessUrl = demoEnabled
                        ? configManager.getProperty("okx.demo.ws.business.url", "")
                        : "";
                okx.configureDemoTrading(demoEnabled, demoRestBaseUrl, demoWsPublicUrl, demoWsBusinessUrl);
            }
        } else {
            exchange.setApiKey(
                    configManager.getBinanceApiKey(),
                    configManager.getBinanceApiSecret(),
                    null
            );
            if (exchange instanceof BinanceExchange binance) {
                boolean testnetEnabled = configManager.getBooleanProperty("binance.testnet.enabled", false);
                String testnetRestBaseUrl = configManager.getProperty("binance.testnet.rest.base.url", "");
                String testnetWsBaseUrl = configManager.getProperty("binance.testnet.ws.base.url", "");
                binance.configureTestnet(testnetEnabled, testnetRestBaseUrl, testnetWsBaseUrl);
            }
        }

        if (configManager.isProxyEnabled()) {
            exchange.setProxy(configManager.getProxyHost(), configManager.getProxyPort());
        }
        return exchange;
    }

    private static List<KLine> loadLocalKLinesIfPresent(ConfigManager configManager,
                                                         Symbol symbol,
                                                         Interval interval) {
        String dataFile = configManager.getProperty("backtest.data.file", "").trim();
        if (dataFile.isEmpty()) {
            return null;
        }
        Path path = Paths.get(dataFile);
        if (!Files.exists(path)) {
            System.out.println("本地数据文件不存在: " + path);
            return null;
        }

        try {
            System.out.println("使用本地数据文件: " + path.toAbsolutePath());
            return CsvKLineLoader.load(path, symbol, interval);
        } catch (Exception e) {
            System.err.println("加载本地 K 线失败: " + e.getMessage());
            return null;
        }
    }

    private static void printUsage() {
        System.out.println("使用方法:");
        System.out.println("  java -jar quant-trading.jar backtest  运行回测");
        System.out.println("  java -jar quant-trading.jar live      启动实盘");
        System.out.println();
        System.out.println("配置文件:");
        System.out.println("  首次运行会从 config.template.properties 创建 config.properties");
        System.out.println("  请在 config.properties 中配置交易所密钥与 live.exchange");
    }
    private static String sanitizeDataDirSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
