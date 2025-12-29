package com.trade.quant;

import com.trade.quant.backtest.*;
import com.trade.quant.core.*;
import com.trade.quant.exchange.*;
import com.trade.quant.execution.*;
import com.trade.quant.market.MarketDataManager;
import com.trade.quant.risk.RiskConfig;
import com.trade.quant.risk.RiskControl;
import com.trade.quant.risk.StopLossManager;
import com.trade.quant.strategy.impl.DualMovingAverageStrategy;
import com.trade.quant.strategy.StrategyConfig;
import com.trade.quant.strategy.StrategyEngine;

import java.math.BigDecimal;
import java.time.Instant;

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
            // 配置回测参数
            Symbol symbol = Symbol.of("BTC-USDT");
            Interval interval = Interval.FIVE_MINUTES;

            BacktestConfig config = BacktestConfig.builder()
                    .symbol(symbol)
                    .interval(interval)
                    .startTime(Instant.parse("2024-01-01T00:00:00Z"))
                    .endTime(Instant.parse("2024-12-01T00:00:00Z"))
                    .initialCapital(BigDecimal.valueOf(10000))
                    .makerFee(BigDecimal.valueOf(0.0002))
                    .takerFee(BigDecimal.valueOf(0.0004))
                    .slippage(BigDecimal.valueOf(0.0005))
                    .leverage(BigDecimal.ONE)
                    .build();

            // 创建交易所
            Exchange exchange = ExchangeFactory.createBinance();
            exchange.setApiKey("YOUR_API_KEY", "YOUR_SECRET_KEY", null);

            // 创建策略
            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(BigDecimal.valueOf(0.01))
                    .cooldownBars(3)
                    .useATRStopLoss(true)
                    .build();

            DualMovingAverageStrategy strategy = new DualMovingAverageStrategy(
                    symbol, interval, 10, 30, strategyConfig
            );

            // 运行回测
            BacktestEngine engine = new BacktestEngine(config, exchange, strategy);
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
            // 创建交易所
            Exchange exchange = ExchangeFactory.createBinance();
            exchange.setApiKey(
                    System.getenv("BINANCE_API_KEY"),
                    System.getenv("BINANCE_SECRET_KEY"),
                    null
            );

            // 创建行情管理器
            MarketDataManager marketDataManager = new MarketDataManager(exchange);

            // 初始化历史数据
            Symbol symbol = Symbol.of("BTC-USDT");
            Interval interval = Interval.FIVE_MINUTES;
            marketDataManager.initializeHistoricalData(symbol, interval, 1000);

            // 创建策略
            StrategyConfig strategyConfig = StrategyConfig.builder()
                    .riskPerTrade(BigDecimal.valueOf(0.01))
                    .cooldownBars(3)
                    .useATRStopLoss(true)
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
    private static void printUsage() {
        System.out.println("使用方法:");
        System.out.println("  java -jar quant-trading.jar backtest  运行回测");
        System.out.println("  java -jar quant-trading.jar live      启动实盘");
        System.out.println();
        System.out.println("环境变量:");
        System.out.println("  BINANCE_API_KEY       Binance API Key");
        System.out.println("  BINANCE_SECRET_KEY    Binance Secret Key");
    }
}
