package com.trade.quant.strategy.impl;

import com.trade.quant.core.*;
import com.trade.quant.indicator.ATR;
import com.trade.quant.indicator.ATRPercentileTracker;
import com.trade.quant.indicator.EMA;
import com.trade.quant.indicator.RSI;
import com.trade.quant.strategy.AbstractStrategy;
import com.trade.quant.strategy.Signal;
import com.trade.quant.strategy.StrategyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 高频波动回归策略（High Frequency Volatility Scalping）
 *
 * <p>策略概述：</p>
 * <ul>
 *   <li>目标：高胜率、小盈亏比、靠频率实现长期正 EV</li>
 *   <li>适用市场：震荡市场、趋势市场中的回调阶段</li>
 *   <li>时间周期：5 分钟 K 线</li>
 * </ul>
 *
 * <p>核心逻辑：</p>
 * <ol>
 *   <li>适用性过滤：仅在 ATR 处于中位区间（30%-70%分位）时交易</li>
 *   <li>进场条件：RSI 极值 + 价格接近 EMA20 + EMA 趋势确认</li>
 *   <li>出场条件：波动回归止盈（优先） > 时间止损 > ATR 风控止损</li>
 * </ol>
 *
 * <p>技术指标：</p>
 * <ul>
 *   <li>RSI(14) - 识别超买超卖</li>
 *   <li>ATR(14) - 动态止损和波动率过滤</li>
 *   <li>EMA(20) - 短期趋势和价格回归基准</li>
 *   <li>EMA(60) - 长期趋势确认</li>
 * </ul>
 */
public class HFVSStrategy extends AbstractStrategy {

    // ==================== 技术指标 ====================
    private final RSI rsi;
    private final ATR atr;
    private final EMA ema20;
    private final EMA ema60;

    // ==================== ATR 分位数管理 ====================
    private final ATRPercentileTracker atrTracker;

    // ==================== 配置参数 ====================
    private final BigDecimal rsiOversold;       // RSI 超卖阈值（默认 25）
    private final BigDecimal rsiOverbought;     // RSI 超买阈值（默认 75）
    private final BigDecimal rsiMeanLower;      // RSI 均值回归下界（默认 45）
    private final BigDecimal rsiMeanUpper;      // RSI 均值回归上界（默认 55）
    private final BigDecimal emaAtrThreshold;   // EMA 距离阈值（ATR 倍数，默认 0.5）
    private final BigDecimal atrStopMultiplier; // ATR 止损倍数（默认 0.8）
    private final int maxHoldingBars;           // 最大持仓 K 线数（默认 6）
    private final BigDecimal atrPercentileMin;  // ATR 分位下限（默认 0.3）
    private final BigDecimal atrPercentileMax;  // ATR 分位上限（默认 0.7）

    // ==================== 持仓状态管理 ====================
    private int holdingBars;                    // 当前持仓 K 线数
    private BigDecimal entryATR;                // 开仓时的 ATR
    private BigDecimal maxLoss;                 // 最大允许亏损（止损线）

    // ==================== 日志 ====================
    private static final Logger tradeLogger = LoggerFactory.getLogger("TRADE_LOGGER");
    private static final Logger systemLogger = LoggerFactory.getLogger(HFVSStrategy.class);

    /**
     * 构造高频波动回归策略
     *
     * @param symbol 交易对
     * @param interval K 线周期
     * @param config 策略配置
     */
    public HFVSStrategy(Symbol symbol, Interval interval, StrategyConfig config) {
        super("HFVS", symbol, interval, config);

        // 初始化技术指标
        this.rsi = new RSI(14);
        this.atr = new ATR(14);
        this.ema20 = new EMA(20);
        this.ema60 = new EMA(60);

        // 初始化 ATR 分位数跟踪器（50根K线窗口，降低样本要求）
        this.atrTracker = new ATRPercentileTracker(50);

        // 从配置管理器读取策略特定参数
        com.trade.quant.core.ConfigManager cfg = com.trade.quant.core.ConfigManager.getInstance();
        // 优化参数以提高胜率
        this.rsiOversold = cfg.getBigDecimalProperty("hfvs.rsi.oversold", BigDecimal.valueOf(30));
        this.rsiOverbought = cfg.getBigDecimalProperty("hfvs.rsi.overbought", BigDecimal.valueOf(70));
        this.rsiMeanLower = cfg.getBigDecimalProperty("hfvs.rsi.mean.lower", BigDecimal.valueOf(45));
        this.rsiMeanUpper = cfg.getBigDecimalProperty("hfvs.rsi.mean.upper", BigDecimal.valueOf(55));
        this.emaAtrThreshold = cfg.getBigDecimalProperty("hfvs.ema.atr.threshold", BigDecimal.valueOf(2.0));
        this.atrStopMultiplier = cfg.getBigDecimalProperty("hfvs.atr.stop.multiplier", BigDecimal.valueOf(2.0)); // 延长：1.5 -> 2.0
        this.maxHoldingBars = cfg.getIntProperty("hfvs.max.holding.bars", 20);    // 大幅延长：12 -> 20根K线（100分钟）
        // 临时禁用 ATR 分位数过滤（设置为全区间）
        this.atrPercentileMin = cfg.getBigDecimalProperty("hfvs.atr.percentile.min", BigDecimal.valueOf(0.0));
        this.atrPercentileMax = cfg.getBigDecimalProperty("hfvs.atr.percentile.max", BigDecimal.valueOf(1.0));

        // 初始化状态
        this.holdingBars = 0;
        this.entryATR = BigDecimal.ZERO;
        this.maxLoss = BigDecimal.ZERO;

        systemLogger.info("高频波动回归策略初始化完成 - 交易对={}, 周期={}, RSI阈值=[{},{}], ATR分位=[{},{}]",
            symbol.toPairString(), interval.getCode(),
            rsiOversold, rsiOverbought,
            atrPercentileMin.multiply(BigDecimal.valueOf(100)),
            atrPercentileMax.multiply(BigDecimal.valueOf(100))
        );
    }

    @Override
    public String getName() {
        return "高频波动回归策略";
    }

    @Override
    public Signal analyze(List<KLine> kLines) {
        // 数据完整性检查
        final int MIN_BARS = 60; // 至少需要 60 根 K 线（保证 EMA60 计算正确）
        if (kLines.size() < MIN_BARS) {
            if (kLines.size() == 1 || kLines.size() % 20 == 0) {
                systemLogger.info("[{}] 数据预热中：{}/{} 根K线", strategyId, kLines.size(), MIN_BARS);
            }
            return null;
        }

        // 更新 K 线计数（AbstractStrategy 提供）
        incrementBarCount();

        // 检查冷却期（AbstractStrategy 提供）
        if (isInCooldown()) {
            if (kLines.size() % 500 == 0) {
                systemLogger.info("[{}] 处于冷却期，跳过分析", strategyId);
            }
            return null;
        }

        // 适用性判断（核心过滤）
        if (!isApplicable(kLines)) {
            if (kLines.size() % 500 == 0) {
                systemLogger.info("[{}] 当前市场条件不适用（ATR 分位数不满足）", strategyId);
            }
            return null;
        }

        // 每1000根K线输出一次运行状态
        if (kLines.size() % 1000 == 0) {
            systemLogger.info("[{}] 运行中... 已处理 {} 根K线", strategyId, kLines.size());
        }

        // 提取数据
        List<BigDecimal> closes = extractCloses(kLines);
        List<BigDecimal> highs = extractHighs(kLines);
        List<BigDecimal> lows = extractLows(kLines);

        // 计算指标
        BigDecimal currentRSI = rsi.latest(closes);
        BigDecimal currentATR = atr.latest(highs, lows, closes);
        BigDecimal currentEMA20 = ema20.latest(closes);
        BigDecimal currentEMA60 = ema60.latest(closes);
        BigDecimal currentPrice = getLatestPrice(kLines);

        // 更新 ATR 分位数跟踪器
        atrTracker.updateATR(currentATR);

        // 计算 |price - EMA20| 距离
        BigDecimal priceToEMA20 = currentPrice.subtract(currentEMA20).abs();
        BigDecimal emaThreshold = currentATR.multiply(emaAtrThreshold);

        // ==================== 做多条件 ====================
        // RSI 超卖 AND 价格远低于 EMA20（深度超卖，回归概率高）AND EMA20 > EMA60（上升趋势确认）
        if (currentRSI.compareTo(rsiOversold) < 0 &&                    // RSI 超卖
            currentPrice.compareTo(currentEMA20) < 0 &&                 // 价格低于 EMA20
            priceToEMA20.compareTo(emaThreshold) > 0 &&                // 价格距EMA20 > 阈值（远离）
            currentEMA20.compareTo(currentEMA60) > 0) {                // EMA20 > EMA60（上升趋势）

            // 计算止损和仓位
            BigDecimal stopLoss = calculateStopLoss(currentPrice, currentATR, Side.BUY);
            BigDecimal quantity = calculatePositionSize(currentPrice, stopLoss);

            // 初始化持仓状态
            holdingBars = 0;
            entryATR = currentATR;
            maxLoss = currentATR.multiply(atrStopMultiplier);

            // 记录交易
            recordTrade();

            // 记录日志
            String reason = String.format(
                "RSI=%.2f<%s (超卖), 价格%.2f低于EMA20(%.2f), 距离=%.2f>%.2f (远离), EMA20(%.2f)>EMA60(%.2f) (趋势向上)",
                currentRSI, rsiOversold, currentPrice, currentEMA20, priceToEMA20, emaThreshold, currentEMA20, currentEMA60
            );
            tradeLogger.info("[{}] {} 做多信号 - 价格={}, 原因={}",
                strategyId, getName(), currentPrice, reason);

            return createLongSignal(kLines, quantity, reason);
        }

        // ==================== 做空条件 ====================
        // RSI 超买 AND 价格远高于 EMA20（深度超买，回归概率高）AND EMA20 < EMA60（下降趋势确认）
        if (currentRSI.compareTo(rsiOverbought) > 0 &&                   // RSI 超买
            currentPrice.compareTo(currentEMA20) > 0 &&                 // 价格高于 EMA20
            priceToEMA20.compareTo(emaThreshold) > 0 &&                // 价格距EMA20 > 阈值（远离）
            currentEMA20.compareTo(currentEMA60) < 0) {                // EMA20 < EMA60（下降趋势）

            BigDecimal stopLoss = calculateStopLoss(currentPrice, currentATR, Side.SELL);
            BigDecimal quantity = calculatePositionSize(currentPrice, stopLoss);

            // 初始化持仓状态
            holdingBars = 0;
            entryATR = currentATR;
            maxLoss = currentATR.multiply(atrStopMultiplier);

            // 记录交易
            recordTrade();

            // 记录日志
            String reason = String.format(
                "RSI=%.2f>%s (超买), 价格%.2f高于EMA20(%.2f), 距离=%.2f>%.2f (远离), EMA20(%.2f)<EMA60(%.2f) (趋势向下)",
                currentRSI, rsiOverbought, currentPrice, currentEMA20, priceToEMA20, emaThreshold, currentEMA20, currentEMA60
            );
            tradeLogger.info("[{}] {} 做空信号 - 价格={}, 原因={}",
                strategyId, getName(), currentPrice, reason);

            return createShortSignal(kLines, quantity, reason);
        }

        // ==================== 调试日志 ====================
        // 每100根K线输出一次当前状态（使用 ATR 样本数作为参考）
        if (atrTracker.getSampleSize() > 0 && atrTracker.getSampleSize() % 100 == 0) {
            systemLogger.info("[{}] 进度: ATR样本={}, RSI={} (区间[{},{}]), 价格={}, EMA20={}, 距离={}/{} (阈值={})",
                strategyId, atrTracker.getSampleSize(), currentRSI, rsiOversold, rsiOverbought,
                currentPrice, currentEMA20, priceToEMA20, emaThreshold, emaThreshold);
        }

        return null;
    }

    @Override
    public Signal onPositionUpdate(Position position, KLine currentKLine, List<KLine> allKLines) {
        // 增加持仓计数
        holdingBars++;

        // ✅ 重新计算指标（使用最新历史数据）
        List<BigDecimal> closes = extractCloses(allKLines);
        List<BigDecimal> highs = extractHighs(allKLines);
        List<BigDecimal> lows = extractLows(allKLines);

        BigDecimal currentRSI = rsi.latest(closes);  // ✅ 实时计算
        BigDecimal currentATR = atr.latest(highs, lows, closes);
        BigDecimal currentEMA20 = ema20.latest(closes);
        BigDecimal currentEMA60 = ema60.latest(closes);

        BigDecimal currentPrice = currentKLine.getClose();

        String exitReason = null;
        BigDecimal unrealizedPnl = position.getUnrealizedPnl();

        // ==================== 1. RSI 回归止盈（持仓15根K线后）====================
        // 检查 RSI 是否回归均值区间
        boolean rsiReverted = currentRSI.compareTo(rsiMeanLower) >= 0 &&
                             currentRSI.compareTo(rsiMeanUpper) <= 0;

        if (holdingBars >= 15 && rsiReverted) {
            exitReason = String.format(
                "RSI回归止盈: RSI=%.2f∈[%.0f,%.0f], 持仓%d根K线, 浮盈=%.2f",
                currentRSI, rsiMeanLower, rsiMeanUpper, holdingBars, unrealizedPnl
            );
            tradeLogger.info("[{}] {} RSI回归出场 - {}",
                strategyId, getName(), exitReason);

            holdingBars = 0;
            return createExitSignal(position.getSide(), position.getQuantity(), exitReason);
        }

        // ==================== 2. 时间止损（必须执行）====================
        if (holdingBars >= maxHoldingBars) {
            exitReason = String.format(
                "时间止损: 持仓%d根K线 >= 限制%d根, 价格=%.2f, 浮盈=%.2f",
                holdingBars, maxHoldingBars, currentPrice, unrealizedPnl
            );
            tradeLogger.warn("[{}] {} 时间止损出场 - {}",
                strategyId, getName(), exitReason);

            holdingBars = 0;
            return createExitSignal(position.getSide(), position.getQuantity(), exitReason);
        }

        // ==================== 3. 强制风控退出（兜底）====================
        if (unrealizedPnl.compareTo(maxLoss.negate()) <= 0) {  // 浮亏 >= maxLoss
            exitReason = String.format(
                "风控止损: 浮盈=%.2f <= 止损线%.2f, 持仓%d根K线",
                unrealizedPnl, maxLoss.negate(), holdingBars
            );
            tradeLogger.error("[{}] {} 风控出场 - {}",
                strategyId, getName(), exitReason);

            holdingBars = 0;
            return createExitSignal(position.getSide(), position.getQuantity(), exitReason);
        }

        return null;
    }

    /**
     * 判断策略是否适用于当前市场条件
     *
     * <p>核心逻辑：当前 ATR 必须处于历史 ATR 分布的中位区间</p>
     * <p>如果样本不足，跳过分位数过滤（策略预热期）</p>
     *
     * @param kLines K 线数据
     * @return true 如果适用，false 否则
     */
    private boolean isApplicable(List<KLine> kLines) {
        // 如果样本不足，跳过 ATR 分位数过滤（策略预热期）
        if (!atrTracker.hasEnoughSamples()) {
            // 每50个样本输出一次
            if (atrTracker.getSampleSize() % 50 == 0) {
                systemLogger.info("[{}] ATR 样本预热中（{}/{}），允许交易",
                    strategyId, atrTracker.getSampleSize(), atrTracker.getWindowSize());
            }
            return true;  // 样本不足时允许交易
        }

        // 获取当前 ATR 在历史分布中的分位数
        BigDecimal currentPercentile = atrTracker.getPercentile();

        // 判断是否在配置的分位区间内
        boolean applicable = currentPercentile.compareTo(atrPercentileMin) >= 0 &&
                            currentPercentile.compareTo(atrPercentileMax) <= 0;

        // 每100根K线输出一次分位数状态
        if (atrTracker.getSampleSize() % 100 == 0) {
            if (applicable) {
                systemLogger.info("[{}] ATR 分位数={}% ∈ [{}%, {}%] - 适用",
                    strategyId,
                    currentPercentile.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                    atrPercentileMin.multiply(BigDecimal.valueOf(100)),
                    atrPercentileMax.multiply(BigDecimal.valueOf(100))
                );
            } else {
                systemLogger.info("[{}] ATR 分位数={}% ∉ [{}%, {}%] - 不适用，跳过",
                    strategyId,
                    currentPercentile.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                    atrPercentileMin.multiply(BigDecimal.valueOf(100)),
                    atrPercentileMax.multiply(BigDecimal.valueOf(100))
                );
            }
        } else if (!applicable && atrTracker.getSampleSize() <= 200) {
            // 样本不足200时，每次不适用都输出（帮助调试）
            systemLogger.info("[{}] ATR 分位数={}%（样本{}/{}）∉ [{}%, {}%] - 不适用",
                strategyId,
                currentPercentile.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                atrTracker.getSampleSize(), atrTracker.getWindowSize(),
                atrPercentileMin.multiply(BigDecimal.valueOf(100)),
                atrPercentileMax.multiply(BigDecimal.valueOf(100))
            );
        }

        return applicable;
    }

    /**
     * 计算 ATR 动态止损
     *
     * @param entryPrice 开仓价
     * @param atr 当前 ATR
     * @param side 方向
     * @return 止损价
     */
    private BigDecimal calculateStopLoss(BigDecimal entryPrice, BigDecimal atr, Side side) {
        BigDecimal stopDistance = atr.multiply(atrStopMultiplier);

        if (side == Side.BUY) {
            // 多头：止损 = 开仓价 - (ATR * 倍数)
            return entryPrice.subtract(stopDistance);
        } else {
            // 空头：止损 = 开仓价 + (ATR * 倍数)
            return entryPrice.add(stopDistance);
        }
    }

    /**
     * 计算仓位大小
     *
     * <p>基于 1% 账户风险原则：position_size = (account_balance * risk%) / stop_loss_distance</p>
     *
     * @param entryPrice 开仓价
     * @param stopLoss 止损价
     * @return 仓位大小（数量）
     */
    private BigDecimal calculatePositionSize(BigDecimal entryPrice, BigDecimal stopLoss) {
        // 止损距离
        BigDecimal stopDistance = entryPrice.subtract(stopLoss).abs();

        if (stopDistance.compareTo(BigDecimal.ZERO) == 0) {
            systemLogger.warn("[{}] 止损距离为0，使用默认最小仓位", strategyId);
            return Decimal.scaleQuantity(BigDecimal.valueOf(0.01));
        }

        // 风险金额（账户余额的 1%）
        // 注意：这里简化处理，实际应该从账户信息获取余额
        // 目前使用 config.getRiskPerTrade() 作为风险金额占账户余额的比例
        BigDecimal riskPercent = config.getRiskPerTrade();  // 例如：0.01 (1%)
        BigDecimal estimatedBalance = BigDecimal.valueOf(10000);  // 假设账户余额 10000 USDT
        BigDecimal riskAmount = estimatedBalance.multiply(riskPercent);

        // 仓位大小 = 风险金额 / 止损距离
        BigDecimal positionSize = riskAmount.divide(stopDistance, 3, RoundingMode.DOWN);

        return Decimal.scaleQuantity(positionSize);
    }

    @Override
    public void reset() {
        super.reset();
        holdingBars = 0;
        entryATR = BigDecimal.ZERO;
        maxLoss = BigDecimal.ZERO;
        atrTracker.reset();
        systemLogger.info("[{}] 策略状态已重置", strategyId);
    }
}
