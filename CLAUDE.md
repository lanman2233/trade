# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 项目概述

这是一个生产级的量化交易系统，用于中心化交易所（Binance、OKX）的 USDT 本位合约。系统优先考虑盈利能力、风险控制和稳定性，而非预测准确性或复杂模型。

**技术栈：** Java 17+, Maven, OkHttp 4.12 (REST/WebSocket), Jackson 2.16 (JSON), SLF4J + Logback (日志)

**核心约束：**
- 仅支持 USDT 本位合约（不支持现货、币本位）
- 交易周期限制为 1m 和 5m（禁止高频）
- 禁止：马丁策略、全仓交易、AI/深度学习模型
- 强制要求：每笔交易必须设置止损（ATR 或固定）
- 每笔交易最大风险：账户资金的 1-2%
- 单策略最大回撤：<30%

## 构建和运行命令

```bash
# 编译项目
mvn compile

# 打包为 JAR（包含依赖）
mvn package

# 运行回测（会自动从 config.properties 读取配置）
java -cp "target/classes;target/dependency/*" com.trade.quant.TradingSystemMain

# 或使用命令行参数
java -cp "target/classes;target/dependency/*" com.trade.quant.TradingSystemMain backtest
java -cp "target/classes;target/dependency/*" com.trade.quant.TradingSystemMain live

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=ClassName

# 清理并重新编译
mvn clean compile
```

**注意：** 在 Windows 上使用分号 `;` 分隔 classpath，在 Linux/Mac 上使用冒号 `:`。

## 配置文件

首次运行时，系统会自动从 `config.template.properties` 创建 `config.properties`。必须填入真实的 API 密钥：

```properties
# 必填项
binance.api.key=YOUR_BINANCE_API_KEY
binance.api.secret=YOUR_BINANCE_API_SECRET

# 可配置项
app.mode=backtest
risk.per.trade=0.01
backtest.start.time=2024-01-01T00:00:00Z
backtest.end.time=2024-12-01T00:00:00Z
```

**重要：** `config.properties` 已被 .gitignore 忽略，不会提交到版本控制。

## 系统架构

系统遵循严格的分层架构，数据单向流动：

```
MarketData → StrategyEngine → RiskControl → OrderExecutor → Exchange
     ↓              ↓              ↓              ↓              ↓
  行情模块        策略引擎        风控模块        执行模块        交易所
```

**核心设计原则：** 每层职责单一，不可绕过：
- **StrategyEngine** 只生成交易意图（Signal），永不执行交易
- **RiskControl** 对所有订单拥有最终否决权
- **Exchange** 层只负责执行，不包含业务逻辑

### 目录结构和数据流

```
项目根目录/
├── src/main/java/com/trade/quant/
│   ├── core/              # 领域模型（Order、Position、KLine 等）
│   │   └── Decimal.java   # BigDecimal 工具类（价格8位、数量3位精度）
│   ├── exchange/          # 交易所抽象层
│   ├── market/            # 行情数据管理（缓存+WebSocket）
│   ├── indicator/         # 技术指标（SMA、EMA、RSI、MACD、BOLL、ATR）
│   ├── strategy/          # 策略框架
│   ├── risk/              # 风险管理（拥有最终否决权）
│   ├── backtest/          # 回测引擎
│   ├── execution/         # 实盘交易执行
│   └── TradingSystemMain.java  # 主入口
├── src/main/resources/
│   └── logback.xml        # 日志配置
├── config.template.properties  # 配置模板（提交到版本控制）
├── config.properties      # 实际配置（不提交，.gitignore）
├── logs/                  # 日志目录（.gitignore）
│   ├── trading.log        # 主日志（30天滚动）
│   └── trades.log         # 交易记录（365天滚动）
└── data/                  # 数据目录（.gitignore）
    ├── orders/            # 订单持久化（JSON格式，崩溃恢复）
    └── trades/            # 交易记录（CSV格式）
```

**数据单向流动：**
```
MarketData → StrategyEngine → RiskControl → OrderExecutor → Exchange
     ↓              ↓              ↓              ↓              ↓
  行情模块        策略引擎        风控模块        执行模块        交易所
```

**核心设计原则：** 每层职责单一，不可绕过：
- **StrategyEngine** 只生成交易意图（Signal），永不执行交易
- **RiskControl** 对所有订单拥有最终否决权
- **Exchange** 层只负责执行，不包含业务逻辑

## 如何添加新策略

1. **继承 AbstractStrategy**（而非直接实现 Strategy - 可免费获得工具方法）：

```java
package com.trade.quant.strategy.impl;

public class MyNewStrategy extends AbstractStrategy {
    public MyNewStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("MyStrategy", symbol, interval, config);
    }

    @Override
    public String getName() {
        return "我的策略名称";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        // 1. 检查冷却期（AbstractStrategy 自动处理）
        if (isInCooldown()) return null;

        // 2. 使用 com.trade.quant.indicator.* 中的指标
        List<BigDecimal> closes = extractCloses(kLines);
        SMA sma = new SMA(20);
        BigDecimal currentSMA = sma.latest(closes);

        // 3. 使用辅助方法生成信号
        if (bullishCondition) {
            recordTrade();  // 必须调用此方法更新冷却期
            return createLongSignal(kLines, calculateQuantity(kLines), "入场原因");
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine) {
        // 持仓时的出场逻辑
        return null;
    }
}
```

2. **在 TradingSystemMain 中注册** 或动态创建策略：

```java
MyNewStrategy strategy = new MyNewStrategy(
    Symbol.of("BTC-USDT"),
    Interval.FIVE_MINUTES,
    StrategyConfig.builder()
        .riskPerTrade(BigDecimal.valueOf(0.01))  // 每笔风险 1%
        .cooldownBars(3)
        .useATRStopLoss(true)
        .build()
);
strategyEngine.addStrategy(strategy);
```

## 关键实现细节

### 金融计算（强制性）
- **所有** 价格、数量、余额必须使用 `BigDecimal` - 永远不要用 `double` 或 `float`
- 使用 `Decimal` 工具类进行常用操作：
  - `Decimal.scalePrice(value)` - 价格格式化（8位小数）
  - `Decimal.scaleQuantity(value)` - 数量格式化（3位小数）
  - `Decimal.divide(a, b)` - 安全除法
  - `Decimal.of(String)` / `Decimal.of(double)` - 创建 BigDecimal

### 订单生命周期
1. **Strategy** 生成 `Signal`（仅交易意图，不包含执行逻辑）
2. **RiskControl.validateAndCreateOrder()** 执行风控检查：
   - 账户余额充足性
   - 连续亏损限制（默认5次）
   - 回撤限制（默认30%）
   - 持仓限制
   - 基于 1-2% 风险自动计算仓位大小
   - 验证止损距离合理性
3. **OrderExecutor.submitOrder()** 幂等性地提交订单到交易所
4. **Persistence** 将订单保存到 `data/orders/`（JSON格式，支持崩溃恢复）

### 行情数据管理
- `MarketDataManager` 维护 K线内存缓存（最多 5000 根）
- **必须先初始化**：`marketDataManager.initializeHistoricalData(symbol, interval, 1000)`
- WebSocket 自动更新缓存并通知所有注册的 `MarketDataListener`
- 获取数据：`marketDataManager.getAllKLines(symbol, interval)`

### 交易所实现
- `BinanceExchange` 已实现 REST 端点和 WebSocket 订阅
- **警告**：`placeOrder()`、`cancelOrder()`、`getOrder()` 当前是桩实现，返回 `UnsupportedOperationException`
- **实盘交易前必须完成这些方法**
- WebSocket 流地址：`wss://fstream.binance.com/ws/{symbol}@kline_{interval}`
- 所有私有 API 请求需要 HMAC-SHA256 签名

### 风险控制
- **RiskControl 拥有最终否决权** - 可拒绝任何策略信号
- 仓位计算公式：`position_size = (account_balance * risk_percent) / stop_loss_distance`
- 实时监控：连续亏损次数、每日亏损、峰值回撤
- 触发风控限制时调用 `riskControl.emergencyStop()` 立即停止所有交易

### 回测引擎
- 完整模拟：maker/taker 手续费、滑点、杠杆
- 输出指标：总收益率、年化收益率、最大回撤、夏普比率、胜率、盈亏比、资金曲线
- **重要**：永远不要在用于验证的同一数据集上优化参数（避免过拟合）
- 建议使用步进分析（walk-forward analysis）进行参数选择

### 日志系统
- 主日志：`logs/trading.log`（30天滚动）
- 交易日志：`logs/trades.log`（365天滚动，专门记录交易信号）
- 通过专用 logger 记录交易：`Logger tradeLogger = LoggerFactory.getLogger("TRADE_LOGGER")`

## 配置系统

系统使用混合配置方式：

### 1. 配置文件（config.properties）
用于管理敏感信息和全局参数：
```properties
# API 密钥（必填）
binance.api.key=YOUR_KEY
binance.api.secret=YOUR_SECRET

# 风控参数
risk.per.trade=0.01
risk.max.drawdown=0.30
risk.max.consecutive.losses=5

# 策略参数
strategy.cooldown.bars=3
strategy.use.atr.stoploss=true

# 回测参数
backtest.start.time=2024-01-01T00:00:00Z
backtest.end.time=2024-12-01T00:00:00Z
backtest.initial.capital=10000
backtest.maker.fee=0.0002
backtest.taker.fee=0.0004
backtest.slippage=0.0005
backtest.leverage=1
```

通过 `ConfigManager.getInstance()` 访问：
```java
ConfigManager config = ConfigManager.getInstance();
String apiKey = config.getBinanceApiKey();
BigDecimal risk = new BigDecimal(config.getProperty("risk.per.trade"));
int cooldown = config.getIntProperty("strategy.cooldown.bars", 3);
```

### 2. Builder 模式（代码配置）
用于创建可复用的配置对象：
```java
BacktestConfig config = BacktestConfig.builder()
    .symbol(Symbol.of("BTC-USDT"))
    .interval(Interval.FIVE_MINUTES)
    .initialCapital(BigDecimal.valueOf(10000))
    .makerFee(BigDecimal.valueOf(0.0002))
    .build();

RiskConfig riskConfig = RiskConfig.builder()
    .riskPerTrade(BigDecimal.valueOf(0.01))
    .maxDrawdownPercent(BigDecimal.valueOf(30))
    .build();

StrategyConfig strategyConfig = StrategyConfig.builder()
    .riskPerTrade(BigDecimal.valueOf(0.01))
    .cooldownBars(3)
    .useATRStopLoss(true)
    .build();
```

## 重要约束和限制

### 必须遵守的约束
1. **策略中不可直接调用交易所 SDK** - 只使用 `Exchange` 接口
2. **止损是强制的** - 每个 `Order` 都必须有止损价（在 Order.Builder 中强制）
3. **仓位大小由 RiskControl 计算** - 策略只决定方向，风控决定仓位
4. **所有时间戳使用 UTC** - 不做时区转换
5. **配置文件安全** - `config.properties` 永远不提交到版本控制

### 当前实现的限制
- `BinanceExchange.placeOrder()` 未实现（返回 `UnsupportedOperationException`）
- 尚无 OKX 交易所实现
- 交易所 API 频率限制的错误处理有限
- 重启后无持仓同步（仅持久化订单记录）
- 无组合级风险管理（仅策略级风控）
- 回测使用简化的手续费模型（无阶梯费率）
- WebSocket 重连机制已实现但未充分测试
- 目前没有单元测试（src/test/ 为空）

### 待办优先级
1. **高优先级**：
   - 完成 `BinanceExchange.placeOrder()` 实现（实盘交易必需）
   - 添加核心模块的单元测试（指标、风控、策略）
   - 改进 WebSocket 重连和错误处理

2. **中优先级**：
   - 实现 OKX 交易所
   - 添加持仓同步机制
   - 完善回测手续费模型

3. **低优先级**：
   - 添加更多示例策略（突破、回撤入场等）
   - 实现策略参数优化器
   - 添加组合级风控

## 常见问题和调试

### 启动问题
- **配置文件缺失**：首次运行会自动从 `config.template.properties` 创建 `config.properties`，需手动填入 API 密钥
- **类路径错误**：Windows 使用 `;` 分隔，Linux/Mac 使用 `:` 分隔
- **编译失败**：确保 Java 版本为 17+（`java -version` 检查）

### 回测问题
- **无数据**：确保网络可访问交易所 API，检查防火墙设置
- **结果异常**：检查回测时间范围是否合理，手续费和滑点设置是否正确
- **策略无信号**：可能处于冷却期，或市场条件不满足入场条件

### 实盘问题
- **API 连接失败**：验证 API 密钥正确性，检查 IP 白名单设置
- **订单未成交**：检查订单价格是否偏离市价太远
- **风控触发**：查看 `logs/trading.log` 确认具体触发了哪个风控限制

### 日志查看
```bash
# 实时查看主日志
tail -f logs/trading.log

# 查看交易记录
cat logs/trades.log

# 搜索错误
grep "ERROR" logs/trading.log

# 查看特定策略的信号
grep "MyStrategy" logs/trades.log
```

### 数据持久化
- 订单记录：`data/orders/{orderId}.json`（用于崩溃恢复）
- 交易记录：`data/trades/{date}.csv`（用于后续分析）
- 定期备份 `data/` 目录以防止数据丢失