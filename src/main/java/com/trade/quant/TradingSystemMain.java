package com.trade.quant;

import com.trade.quant.backtest.*;
import com.trade.quant.core.*;
import com.trade.quant.exchange.*;
import com.trade.quant.execution.*;
import com.trade.quant.market.MarketDataManager;
import com.trade.quant.risk.RiskConfig;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.impl.BtcDonchian48BreakoutStrategy;
import com.trade.quant.strategy.impl.BtcMa200Rsi6TrendStrategy;
import com.trade.quant.strategy.impl.DualMovingAverageStrategy;
import com.trade.quant.strategy.impl.HFVSStrategy;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.StrategyEngine;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 量化交易系统主类
 */
public class TradingSystemMain {

    public static void main(String[] args) {
        System.out.println("""
            ================================================
               量化交易系统 v1.0
               赚钱优先 · 风控第一 · 稳定运行
            ================================================
            """);

        // 根据命令行参数选择模式
        runBacktest();
        /*if (args.length > 0 && "backtest".equals(args[0])) {
            runBacktest();
        } else if (args.length > 0 && "live".equals(args[0])) {
            runLive();
        } else {
            printUsage();
        }*/
    }

    /**
     * 运行回测
     */
    private static void runBacktest() {
        System.out.println("启动回测模式...\n");

        try {
            // 加载配置
            ConfigManager configManager = ConfigManager.getInstance();

            // 配置回测参数
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

            // 创建交易所并从配置文件加载 API 密钥
            Exchange exchange = ExchangeFactory.createBinance();
            exchange.setApiKey(
                configManager.getBinanceApiKey(),
                configManager.getBinanceApiSecret(),
                null
            );

            // 🆕 设置代理（从配置文件读取）
            if (configManager.isProxyEnabled()) {
                String proxyHost = configManager.getProxyHost();
                int proxyPort = configManager.getProxyPort();
                System.out.println("使用代理: " + proxyHost + ":" + proxyPort);
                exchange.setProxy(proxyHost, proxyPort);
            }

            // 创建策略（从配置文件加载参数）
            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(new BigDecimal(configManager.getProperty("risk.per.trade")))
                    .cooldownBars(configManager.getIntProperty("strategy.cooldown.bars", 3))
                    .useATRStopLoss(configManager.getBooleanProperty("strategy.use.atr.stoploss", true))
                    .atrStopLossMultiplier(new BigDecimal("0.8"))
                    .build();

            // ==================== 策略选择 ====================
            // 使用 HFVS 策略（高频波动回归）
            BtcDonchian48BreakoutStrategy strategy = new BtcDonchian48BreakoutStrategy(
                    symbol, interval, strategyConfig
            );

            // 如果想使用双均线策略，可以替换为：
            // DualMovingAverageStrategy strategy = new DualMovingAverageStrategy(
            //         symbol, interval, 10, 30, strategyConfig
            // );

            System.out.println("使用策略: " + strategy.getName());
            System.out.println("交易对: " + symbol.toPairString());
            System.out.println("周期: " + interval.getCode());
            System.out.println();

            // 运行回测
            BacktestTradeLogger tradeLogger = new BacktestTradeLogger("logs/backtest-trades.csv");
            BacktestEngine engine = new BacktestEngine(
                    config,
                    exchange,
                    strategy,
                    tradeLogger,
                    loadLocalKLinesIfPresent(configManager, symbol, interval)
            );
            BacktestResult result = engine.run();

            // 输出结果
            System.out.println(result);

        } catch (Exception e) {
            System.err.println("回测失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 运行实盘
     */
    private static void runLive() {
        System.out.println("启动实盘模式...\n");
        System.out.println("警告: 实盘交易涉及真实资金，请确保:");
        System.out.println("1. 已完成充分回测");
        System.out.println("2. 风控参数已正确设置");
        System.out.println("3. 有足够的资金承受损失");
        System.out.print("\n确认启动实盘? (yes/no): ");

        // 简化处理，实际应该读取用户输入
        System.out.println("no");
        System.out.println("已取消实盘启动");

        /*
        try {
            // 加载配置
            ConfigManager configManager = ConfigManager.getInstance();

            // 创建交易所并从配置文件加载 API 密钥
            Exchange exchange = ExchangeFactory.createBinance();
            exchange.setApiKey(
                    configManager.getBinanceApiKey(),
                    configManager.getBinanceApiSecret(),
                    null
            );

            // 创建行情管理器
            MarketDataManager marketDataManager = new MarketDataManager(exchange);

            // 初始化历史数据
            Symbol symbol = Symbol.of("BTC-USDT");
            Interval interval = Interval.FIVE_MINUTES;
            marketDataManager.initializeHistoricalData(symbol, interval, 1000);

            // 创建策略（从配置文件加载参数）
            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(new BigDecimal(configManager.getProperty("risk.per.trade")))
                    .cooldownBars(configManager.getIntProperty("strategy.cooldown.bars", 3))
                    .useATRStopLoss(configManager.getBooleanProperty("strategy.use.atr.stoploss", true))
                    .build();

            DualMovingAverageStrategy strategy = new DualMovingAverageStrategy(
                    symbol, interval, 10, 30, strategyConfig
            );

            // 创建策略引擎
            StrategyEngine strategyEngine = new StrategyEngine(marketDataManager);
            strategyEngine.addStrategy(strategy);

            // 创建风控
            RiskConfig riskConfig = RiskConfig.builder()
                    .riskPerTrade(BigDecimal.valueOf(0.01))
                    .maxDrawdownPercent(BigDecimal.valueOf(30))
                    .build();

            AccountInfo accountInfo = exchange.getAccountInfo();
            RiskControl riskControl = new RiskControl(riskConfig, accountInfo);

            // 创建执行器
            Persistence persistence = new FilePersistence("data/orders");
            OrderExecutor orderExecutor = new OrderExecutor(exchange, persistence);

            // 创建止损管理器
            StopLossManager stopLossManager = new StopLossManager(exchange);

            // 创建交易引擎
            TradingEngine tradingEngine = new TradingEngine(
                    strategyEngine,
                    riskControl,
                    orderExecutor,
                    stopLossManager,
                    exchange
            );

            // 启动
            tradingEngine.start();

            System.out.println("实盘交易已启动，按 Ctrl+C 退出...");

            // 保持运行
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("实盘启动失败: " + e.getMessage());
            e.printStackTrace();
        }
        */
    }

    /**
     * 打印使用说明
     */
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
            System.err.println("加载本地K线失败: " + e.getMessage());
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
        System.out.println("  请在 config.properties 中配置您的 API 密钥");
        System.out.println();
        System.out.println("配置项:");
        System.out.println("  binance.api.key       Binance API Key");
        System.out.println("  binance.api.secret    Binance API Secret");
        System.out.println("  risk.per.trade        每笔交易风险比例 (如: 0.01 表示 1%)");
        System.out.println("  backtest.start.time   回测开始时间 (如: 2024-01-01T00:00:00Z)");
        System.out.println("  backtest.end.time     回测结束时间 (如: 2024-12-01T00:00:00Z)");
        System.out.println();
        System.out.println("安全提示:");
        System.out.println("  - 请勿将 config.properties 提交到版本控制系统");
        System.out.println("  - config.properties 已被 .gitignore 忽略");
        System.out.println("  - 仅提交 config.template.properties 模板文件");
    }
}
