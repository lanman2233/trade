# 量化交易系统

一个生产级的 Java 量化交易系统，专注于 USDT 本位合约交易。

**核心理念：赚钱优先 · 风控第一 · 稳定运行**

## 特性

- ✅ **严格风控** - 每笔交易风险 1-2%，强制止损，最大回撤 <30%
- ✅ **完整架构** - 行情采集 → 策略引擎 → 风控模块 → 订单执行 → 交易所
- ✅ **回测引擎** - 支持手续费、滑点模拟，输出完整回测报告
- ✅ **多交易所** - 抽象接口设计，已实现 Binance，OKX 可扩展
- ✅ **技术指标** - SMA、EMA、RSI、MACD、BOLL、ATR 等常用指标
- ✅ **策略框架** - 提供抽象基类，快速开发自定义策略
- ✅ **数据持久化** - 订单持久化，支持崩溃恢复
- ✅ **实时监控** - WebSocket 行情订阅，自动断线重连

## 系统约束

| 约束项 | 说明 |
|--------|------|
| 交易市场 | 仅支持中心化交易所 USDT 本位合约 |
| 交易周期 | 仅支持 1m、5m K线（禁止高频） |
| 风险控制 | 禁止马丁、禁止全仓 |
| 每笔风险 | ≤ 1-2% 账户资金 |
| 止损要求 | 必须设置止损（ATR 或固定） |
| 最大回撤 | 单策略 < 30% |

## 系统架构

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ MarketData  │───▶│  Strategy   │───▶│   Risk      │───▶│   Order     │
│   行情模块   │    │   Engine    │    │  Control    │    │  Executor   │
│             │    │   策略引擎   │    │   风控模块   │    │   执行模块   │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
                                                           │
                                                           ▼
                                                    ┌─────────────┐
                                                    │  Exchange   │
                                                    │   交易所    │
                                                    └─────────────┘
```

**核心原则：**
- **StrategyEngine** 只产出交易意图（Signal），不执行交易
- **RiskControl** 拥有最终否决权
- **Exchange** 层只负责执行，不包含业务逻辑

## 快速开始

### 环境要求

- Java 17+
- Maven 3.x

### 编译项目

```bash
mvn compile
```

### 打包

```bash
mvn package
```

### 运行回测

```bash
java -cp target/classes com.trade.quant.TradingSystemMain backtest
```

### 运行实盘（需配置 API 密钥）

```bash
export BINANCE_API_KEY="your_api_key"
export BINANCE_SECRET_KEY="your_secret_key"

java -cp target/classes com.trade.quant.TradingSystemMain live
```

## 项目结构

```
src/main/java/com/trade/quant/
├── core/                  # 核心领域模型
│   ├── Symbol.java       # 交易对
│   ├── Order.java        # 订单
│   ├── Position.java     # 持仓
│   ├── KLine.java        # K线数据
│   └── Decimal.java      # BigDecimal 工具类
├── exchange/             # 交易所抽象层
│   ├── Exchange.java             # 交易所接口
│   ├── BinanceExchange.java      # Binance 实现
│   └── ExchangeFactory.java      # 工厂类
├── market/               # 行情数据模块
│   ├── MarketDataManager.java   # 行情管理器
│   └── MarketDataListener.java  # 监听器接口
├── indicator/            # 技术指标
│   ├── SMA.java, EMA.java        # 移动平均线
│   ├── RSI.java                  # 相对强弱指标
│   ├── MACD.java                 # 平滑异同移动平均
│   ├── BOLL.java                 # 布林带
│   └── ATR.java                  # 平均真实波幅
├── strategy/             # 策略引擎
│   ├── Strategy.java             # 策略接口
│   ├── AbstractStrategy.java     # 策略基类
│   ├── StrategyEngine.java       # 策略引擎
│   ├── Signal.java               # 交易信号
│   └── impl/
│       └── DualMovingAverageStrategy.java  # 双均线策略示例
├── risk/                 # 风控模块
│   ├── RiskControl.java          # 风控核心
│   ├── RiskConfig.java           # 风控配置
│   └── StopLossManager.java      # 止损管理器
├── backtest/             # 回测引擎
│   ├── BacktestEngine.java       # 回测引擎
│   ├── BacktestConfig.java       # 回测配置
│   └── BacktestResult.java       # 回测结果
└── execution/            # 实盘执行
    ├── TradingEngine.java        # 交易引擎
    ├── OrderExecutor.java        # 订单执行器
    └── FilePersistence.java      # 文件持久化
```

## 开发指南

### 创建自定义策略

继承 `AbstractStrategy` 并实现 `analyze()` 方法：

```java
package com.trade.quant.strategy.impl;

import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.core.*;
import com.trade.quant.strategy.*;
import com.trade.quant.indicator.SMA;

import java.math.BigDecimal;
import java.util.List;

public class MyStrategy extends AbstractStrategy {

    public MyStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("MyStrategy", symbol, interval, config);
    }

    @Override
    public String getName() {
        return "我的策略";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        // 1. 检查冷却期
        if (isInCooldown()) return null;

        // 2. 技术分析
        List<BigDecimal> closes = extractCloses(kLines);
        SMA sma = new SMA(20);
        BigDecimal currentSMA = sma.latest(closes);
        BigDecimal currentPrice = getLatestPrice(kLines);

        // 3. 生成信号
        if (currentPrice.compareTo(currentSMA) > 0) {
            // 金叉入场
            recordTrade();
            return createLongSignal(kLines, BigDecimal.valueOf(0.01), "价格突破均线");
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

### 使用策略

```java
// 创建策略
StrategyConfig config = StrategyConfig.builder()
    .riskPerTrade(BigDecimal.valueOf(0.01))  // 每笔风险 1%
    .cooldownBars(3)                          // 冷却 3 根 K线
    .useATRStopLoss(true)                     // 使用 ATR 止损
    .build();

MyStrategy strategy = new MyStrategy(
    Symbol.of("BTC-USDT"),
    Interval.FIVE_MINUTES,
    config
);

// 添加到策略引擎
StrategyEngine engine = new StrategyEngine(marketDataManager);
engine.addStrategy(strategy);
engine.start();
```

### 风控配置

```java
RiskConfig riskConfig = RiskConfig.builder()
    .riskPerTrade(BigDecimal.valueOf(0.01))           // 每笔风险 1%
    .maxPositionRatio(BigDecimal.valueOf(0.1))        // 最大仓位 10%
    .maxStopLossPercent(BigDecimal.valueOf(5))        // 最大止损 5%
    .maxConsecutiveLosses(3)                          // 最大连续亏损 3 次
    .maxDrawdownPercent(BigDecimal.valueOf(30))       // 最大回撤 30%
    .build();

RiskControl riskControl = new RiskControl(riskConfig, accountInfo);
```

## 回测示例

```java
// 配置回测参数
BacktestConfig config = BacktestConfig.builder()
    .symbol(Symbol.of("BTC-USDT"))
    .interval(Interval.FIVE_MINUTES)
    .startTime(Instant.parse("2024-01-01T00:00:00Z"))
    .endTime(Instant.parse("2024-12-01T00:00:00Z"))
    .initialCapital(BigDecimal.valueOf(10000))
    .makerFee(BigDecimal.valueOf(0.0002))
    .takerFee(BigDecimal.valueOf(0.0004))
    .slippage(BigDecimal.valueOf(0.0005))
    .build();

// 运行回测
BacktestEngine engine = new BacktestEngine(config, exchange, strategy);
BacktestResult result = engine.run();

// 输出结果
System.out.println(result);
```

**回测输出指标：**
- 总收益率、年化收益率
- 最大回撤
- 夏普比率
- 胜率、盈亏比
- 平均盈利/亏损
- 资金曲线

## 重要提示

### ⚠️ 实盘交易前必读

1. **充分回测** - 在模拟环境充分验证策略
2. **小资金测试** - 先用小资金实盘测试
3. **监控日志** - 实时监控 `logs/trading.log`
4. **设置风控** - 严格配置最大回撤和连续亏损限制
5. **定期检查** - 定期检查持仓和订单状态

### 🚫 禁止事项

- ❌ 禁止在策略层直接调用交易所 SDK
- ❌ 禁止在实盘中使用未充分回测的策略
- ❌ 禁止修改风控限制以规避检查
- ❌ 禁止在生产环境使用调试日志

### 💡 最佳实践

- ✅ 所有金额计算使用 `BigDecimal`
- ✅ 时间统一使用 UTC 时区
- ✅ 订单必须设置止损价格
- ✅ 使用抽象类而非直接实现接口
- ✅ 编写单元测试覆盖核心逻辑

## 待办事项

- [ ] 完善 BinanceExchange 下单功能
- [ ] 实现 OKX 交易所
- [ ] 添加更多示例策略（突破、回撤入场等）
- [ ] 增加单元测试覆盖率
- [ ] 优化 WebSocket 断线重连机制
- [ ] 添加策略参数优化器
- [ ] 实现组合级风控

## 技术栈

- **Java 17** - 核心语言
- **Maven** - 项目管理
- **OkHttp 4.12** - HTTP 客户端
- **Jackson 2.16** - JSON 处理
- **SLF4J + Logback** - 日志框架
- **JUnit 5** - 单元测试

