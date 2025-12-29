# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 项目概述

这是一个生产级的量化交易系统，用于中心化交易所（Binance、OKX）的 USDT 本位合约。系统优先考虑盈利能力、风险控制和稳定性，而非预测准确性或复杂模型。

**技术栈：** Java 17+, Maven, OkHttp (REST/WebSocket), Jackson (JSON), Logback (日志)

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

# 打包为 JAR
mvn package

# 运行回测
java -cp target/classes:target/dependency/* com.trade.quant.TradingSystemMain backtest

# 运行实盘交易（需要在环境变量中设置 API 密钥）
export BINANCE_API_KEY="your_key"
export BINANCE_SECRET_KEY="your_secret"
java -cp target/classes:target/dependency/* com.trade.quant.TradingSystemMain live

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=ClassName

# 清理构建
mvn clean compile
```

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

### 包结构

```
com.trade.quant.*
├── core/              # 领域模型（Order、Position、KLine 等）
│   └── Decimal.java   # 所有金融计算使用 BigDecimal
├── exchange/          # 交易所抽象层
│   ├── Exchange.java          # 接口（扩展此接口实现 OKX）
│   ├── BinanceExchange.java   # Binance 合约实现
│   └── ExchangeFactory.java   # 工厂模式
├── market/            # 行情数据管理
│   ├── MarketDataManager.java   # 缓存 + 历史数据
│   └── MarketDataListener.java  # WebSocket 回调接口
├── indicator/         # 技术指标（SMA、EMA、RSI、MACD、BOLL、ATR）
├── strategy/          # 策略框架
│   ├── Strategy.java           # 策略接口
│   ├── AbstractStrategy.java   # 基类，提供工具方法
│   ├── StrategyEngine.java     # 多策略协调器
│   ├── Signal.java             # 交易信号（入场/出场意图）
│   └── impl/
│       └── DualMovingAverageStrategy.java  # 示例趋势跟随策略
├── risk/              # 风险管理（关键 - 拥有否决权）
│   ├── RiskControl.java        # 仓位计算、回撤监控
│   ├── RiskConfig.java         # 风险限制（每笔 1-2% 等）
│   └── StopLossManager.java    # 止损监控 + 追踪止损
├── backtest/          # 回测引擎
│   ├── BacktestEngine.java     # 模拟交易，包含滑点/手续费
│   ├── BacktestConfig.java
│   └── BacktestResult.java     # 指标：收益率、夏普、最大回撤、胜率
└── execution/         # 实盘交易执行
    ├── TradingEngine.java      # 协调所有组件
    ├── OrderExecutor.java      # 幂等下单 + 恢复
    ├── Persistence.java        # 订单持久化接口
    └── FilePersistence.java    # 基于文件的 JSON 实现
```

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

### 金融计算
- **所有** 价格、数量、余额必须使用 `BigDecimal` - 永远不要用 `double` 或 `float`
- 使用 `Decimal` 工具类进行常用操作（scalePrice、scaleQuantity、divide）
- 价格：8 位小数精度
- 数量：3 位小数精度（每个交易所可配置）

### 订单流程
1. Strategy 生成 `Signal`（仅意图，无执行逻辑）
2. `RiskControl.validateAndCreateOrder()` 检查：
   - 账户余额
   - 连续亏损限制
   - 回撤限制
   - 持仓限制
   - 基于 1-2% 风险计算仓位大小
   - 验证止损距离
3. 如果通过，`OrderExecutor.submitOrder()` 幂等地下单
4. `Persistence` 保存订单以备崩溃恢复

### 行情数据管理
- `MarketDataManager` 维护 K线缓存（最多 5000 根）
- 订阅前先用 `initializeHistoricalData(symbol, interval, count)` 初始化
- WebSocket 自动更新缓存并通知监听器
- 调用 `getAllKLines(symbol, interval)` 获取缓存数据

### 交易所实现说明
- `BinanceExchange` 实现了 REST 端点和 WebSocket 订阅
- `placeOrder()`、`cancelOrder()`、`getOrder()` 是桩实现 - **实盘交易前必须完成这些方法**
- WebSocket 流：`wss://fstream.binance.com/ws/{symbol}@kline_{interval}`
- 签名请求需要 HMAC-SHA256 签名

### 风险控制
- **拥有最终否决权** - 可拒绝任何信号
- 仓位计算公式：`position_size = (account_balance * risk_percent) / stop_loss_distance`
- 监控：连续亏损、每日亏损、峰值回撤
- 调用 `riskControl.emergencyStop()` 立即停止所有交易

### 回测
- 包含：maker/taker 手续费、滑点、杠杆
- 输出：总收益、年化收益、最大回撤、夏普比率、胜率、盈亏比
- **永远不要在用于验证的同一数据集上优化参数**
- 建议使用步进分析进行参数选择

## 配置

所有配置通过 Builder 模式完成（无外部配置文件）：

- `BacktestConfig.builder()`：交易对、周期、日期范围、资金、手续费、滑点、杠杆
- `RiskConfig.builder()`：每笔风险、最大仓位比例、止损百分比、连续亏损、最大回撤
- `StrategyConfig.builder()`：每笔风险、冷却K线数、ATR止损开关、成交量确认

## 重要约束

1. **策略中不可直接调用 SDK** - 只使用 `Exchange` 接口
2. **止损是强制的**，每个 `Order` 都必须有（在 Order.Builder 中强制执行）
3. **仓位大小由 RiskControl 计算**，而非由策略决定
4. **所有时间戳使用 UTC** - 无时区转换
5. **WebSocket 重连** 已处理但未完全测试
6. **订单持久化** 确保崩溃后恢复 - 已完成订单存储在 `data/orders/`

## 当前限制

- `BinanceExchange.placeOrder()` 未实现（返回 UnsupportedOperationException）
- 尚无 OKX 实现
- 交易所 API 频率限制的错误处理有限
- 重启后无持仓同步（仅持久化订单）
- 无组合级风险管理（仅策略级）
- 回测使用简化的手续费模型（无阶梯费率）

## 测试策略

目前测试很少。优先级：
1. 所有指标的单元测试（用已知值验证）
2. RiskControl 仓位计算测试
3. DualMovingAverageStrategy 的信号生成测试
4. 回测结果可复现性（相同输入 = 相同输出）
5. OrderExecutor 幂等性和恢复测试
