package com.trade.quant.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trade.quant.strategy.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略健康检查器
 *
 * 职责：
 * - 监控每个策略的健康状态
 * - 在策略表现恶化时自动禁用
 * - 维护策略生命周期状态（ENABLED, DEGRADED, DISABLED）
 * - 支持状态持久化和崩溃恢复
 *
 * 自动禁用条件：
 * 1. 连续亏损次数 > 阈值
 * 2. EV < 0 且连续亏损 >= 阈值
 *
 * 降级警告条件：
 * - EV 为负但尚未达到禁用阈值
 */
public class StrategyHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(StrategyHealthChecker.class);
    private static final Logger HEALTH_LOGGER = LoggerFactory.getLogger("HEALTH_LOGGER");

    private final RollingEVCalculator evCalculator;
    private final HealthCheckConfig config;

    // 策略状态管理
    private final Map<String, StrategyState> strategyStates;

    // 状态持久化文件路径
    private final String stateFilePath;

    private final ObjectMapper objectMapper;

    /**
     * 创建健康检查器
     *
     * @param evCalculator EV 计算器（共享实例）
     * @param config 健康检查配置
     */
    public StrategyHealthChecker(RollingEVCalculator evCalculator, HealthCheckConfig config) {
        this.evCalculator = evCalculator;
        this.config = config;
        this.strategyStates = new ConcurrentHashMap<>();

        // 创建数据目录
        File dataDir = new File("data/monitor");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        this.stateFilePath = "data/monitor/health-state.json";

        // 初始化 JSON 序列化器
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 加载持久化状态
        loadState();

        logger.info("StrategyHealthChecker 初始化完成，配置: {}", config);
    }

    /**
     * 注册策略
     * 在 StrategyEngine.addStrategy() 中调用
     *
     * @param strategy 要注册的策略
     */
    public void registerStrategy(Strategy strategy) {
        String strategyId = strategy.getStrategyId();

        // 如果配置为自动启用且策略未注册，则设置为 ENABLED
        if (config.isAutoEnable() && !strategyStates.containsKey(strategyId)) {
            strategyStates.put(strategyId, StrategyState.ENABLED);
            logger.info("策略已注册并自动启用: {}", strategyId);
        } else if (!strategyStates.containsKey(strategyId)) {
            // 不自动启用，默认禁用（需要手动启用）
            strategyStates.put(strategyId, StrategyState.DISABLED);
            logger.info("策略已注册（禁用状态）: {}", strategyId);
        } else {
            logger.debug("策略已存在，跳过注册: {}", strategyId);
        }
    }

    /**
     * 检查策略健康状态
     * 在每次平仓后调用
     *
     * @param strategyId 策略 ID
     */
    public void checkStrategyHealth(String strategyId) {
        if (!config.isEnabled()) {
            return; // 健康检查功能已禁用
        }

        // 获取当前状态
        StrategyState currentState = strategyStates.get(strategyId);
        if (currentState == null) {
            logger.debug("策略 {} 未注册，跳过健康检查", strategyId);
            return;
        }

        if (currentState == StrategyState.DISABLED) {
            // 已禁用的策略不再重新评估（需要手动重新启用）
            logger.debug("策略 {} 已禁用，跳过健康检查", strategyId);
            return;
        }

        // 计算滚动指标
        RollingMetrics metrics = evCalculator.calculateMetrics(strategyId);

        // 检查是否有足够样本
        if (!metrics.hasSufficientData(config.getMinSampleSize())) {
            logger.debug("策略 {} 样本不足（{}/{}），跳过健康检查",
                        strategyId, metrics.getSampleSize(), config.getMinSampleSize());
            return;
        }

        // 评估健康状况
        HealthCheckResult result = evaluateHealth(metrics);

        // 根据结果更新状态
        if (result.shouldDisable()) {
            disableStrategy(strategyId, result);
        } else if (result.isDegraded()) {
            markDegraded(strategyId, result);
        } else if (currentState == StrategyState.DEGRADED && result.isHealthy()) {
            recoverStrategy(strategyId);
        }
    }

    /**
     * 评估策略健康状态
     *
     * @param metrics 滚动指标
     * @return 健康检查结果
     */
    private HealthCheckResult evaluateHealth(RollingMetrics metrics) {
        List<String> reasons = new java.util.ArrayList<>();
        boolean shouldDisable = false;
        boolean degraded = false;

        // 检查 1: 连续亏损超过阈值
        if (metrics.getConsecutiveLosses() > config.getMaxConsecutiveLosses()) {
            reasons.add(String.format(
                "连续亏损次数（%d）超过阈值（%d）",
                metrics.getConsecutiveLosses(), config.getMaxConsecutiveLosses()
            ));
            shouldDisable = true;
        }

        // 检查 2: EV 为负且连续亏损 >= 阈值
        if (metrics.getRollingEV().compareTo(config.getMinEV()) < 0) {
            if (metrics.getConsecutiveLosses() >= config.getMaxConsecutiveLosses()) {
                reasons.add(String.format(
                    "EV 为负（%s）且连续亏损（%d）达到阈值（%d）",
                    metrics.getRollingEV(), metrics.getConsecutiveLosses(), config.getMaxConsecutiveLosses()
                ));
                shouldDisable = true;
            } else if (metrics.getSampleSize() >= config.getMinEVNegativeTrades()) {
                // 样本足够且 EV 为负，但连续亏损未达阈值 -> 降级警告
                reasons.add(String.format(
                    "EV 为负（%s）且样本数（%d）达到阈值（%d）",
                    metrics.getRollingEV(), metrics.getSampleSize(), config.getMinEVNegativeTrades()
                ));
                degraded = true;
            }
        }

        if (shouldDisable) {
            return HealthCheckResult.disable(reasons);
        } else if (degraded) {
            return HealthCheckResult.degraded(reasons);
        } else {
            return HealthCheckResult.healthy();
        }
    }

    /**
     * 禁用策略
     *
     * @param strategyId 策略 ID
     * @param result 健康检查结果
     */
    private void disableStrategy(String strategyId, HealthCheckResult result) {
        strategyStates.put(strategyId, StrategyState.DISABLED);

        String message = String.format(
            "[STRATEGY_DISABLED] %s | 原因: %s | 指标: %s | 时间: %s",
            strategyId,
            String.join("; ", result.getReasons()),
            evCalculator.calculateMetrics(strategyId),
            Instant.now().toString()
        );

        logger.warn(message);
        HEALTH_LOGGER.info(message);

        // 持久化状态
        saveState();
    }

    /**
     * 标记策略为降级状态
     *
     * @param strategyId 策略 ID
     * @param result 健康检查结果
     */
    private void markDegraded(String strategyId, HealthCheckResult result) {
        strategyStates.put(strategyId, StrategyState.DEGRADED);

        String message = String.format(
            "[STRATEGY_DEGRADED] %s | 原因: %s | 指标: %s | 时间: %s",
            strategyId,
            String.join("; ", result.getReasons()),
            evCalculator.calculateMetrics(strategyId),
            Instant.now().toString()
        );

        logger.warn(message);
        HEALTH_LOGGER.info(message);

        // 持久化状态
        saveState();
    }

    /**
     * 策略从降级状态恢复
     *
     * @param strategyId 策略 ID
     */
    private void recoverStrategy(String strategyId) {
        strategyStates.put(strategyId, StrategyState.ENABLED);

        String message = String.format(
            "[STRATEGY_RECOVERED] %s | 指标: %s | 时间: %s",
            strategyId,
            evCalculator.calculateMetrics(strategyId),
            Instant.now().toString()
        );

        logger.info(message);
        HEALTH_LOGGER.info(message);

        // 持久化状态
        saveState();
    }

    /**
     * 检查策略是否启用（可交易）
     * 在 StrategyEngine.processStrategy() 中调用
     *
     * @param strategyId 策略 ID
     * @return true 如果策略启用或降级，false 如果已禁用
     */
    public boolean isStrategyEnabled(String strategyId) {
        StrategyState state = strategyStates.get(strategyId);

        // 未注册的策略默认启用
        if (state == null) {
            return true;
        }

        // ENABLED 和 DEGRADED 状态都可以交易
        return state == StrategyState.ENABLED || state == StrategyState.DEGRADED;
    }

    /**
     * 手动重新启用策略
     * 操作员在确定策略可以重新交易时调用
     *
     * @param strategyId 策略 ID
     */
    public void enableStrategy(String strategyId) {
        StrategyState oldState = strategyStates.get(strategyId);
        strategyStates.put(strategyId, StrategyState.ENABLED);

        String message = String.format(
            "[STRATEGY_MANUAL_ENABLE] %s | 原状态: %s | 时间: %s",
            strategyId, oldState, Instant.now().toString()
        );

        logger.info(message);
        HEALTH_LOGGER.info(message);

        // 持久化状态
        saveState();
    }

    /**
     * 手动禁用策略
     * 操作员主动禁用策略时调用
     *
     * @param strategyId 策略 ID
     */
    public void disableStrategyManually(String strategyId) {
        StrategyState oldState = strategyStates.get(strategyId);
        strategyStates.put(strategyId, StrategyState.DISABLED);

        String message = String.format(
            "[STRATEGY_MANUAL_DISABLE] %s | 原状态: %s | 时间: %s",
            strategyId, oldState, Instant.now().toString()
        );

        logger.info(message);
        HEALTH_LOGGER.info(message);

        // 持久化状态
        saveState();
    }

    /**
     * 获取所有策略状态
     *
     * @return 策略 ID -> 状态的映射
     */
    public Map<String, StrategyState> getStrategyStates() {
        return new HashMap<>(strategyStates);
    }

    /**
     * 获取策略状态
     *
     * @param strategyId 策略 ID
     * @return 策略状态，如果不存在则返回 null
     */
    public StrategyState getStrategyState(String strategyId) {
        return strategyStates.get(strategyId);
    }

    /**
     * 保存状态到文件
     */
    private void saveState() {
        try {
            Map<String, String> stateData = new HashMap<>();

            // 转换为可序列化的格式
            strategyStates.forEach((k, v) -> {
                stateData.put(k, v.name());
                stateData.put(k + ".lastUpdated", Instant.now().toString());
            });

            objectMapper.writeValue(new File(stateFilePath), stateData);
            logger.debug("健康检查状态已保存到 {}", stateFilePath);
        } catch (IOException e) {
            logger.error("保存健康检查状态失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从文件加载状态
     */
    private void loadState() {
        try {
            File stateFile = new File(stateFilePath);
            if (!stateFile.exists()) {
                logger.info("健康检查状态文件不存在，将从空状态开始");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> stateData = objectMapper.readValue(stateFile, Map.class);

            // 恢复状态
            stateData.forEach((key, value) -> {
                if (!key.endsWith(".lastUpdated")) {
                    try {
                        StrategyState state = StrategyState.valueOf(value);
                        strategyStates.put(key, state);
                    } catch (IllegalArgumentException e) {
                        logger.warn("无效的策略状态: {} = {}", key, value);
                    }
                }
            });

            logger.info("已恢复 {} 个策略的健康状态", strategyStates.size());
        } catch (IOException e) {
            logger.error("加载健康检查状态失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取配置
     */
    public HealthCheckConfig getConfig() {
        return config;
    }

    /**
     * 获取 EV 计算器
     */
    public RollingEVCalculator getEvCalculator() {
        return evCalculator;
    }
}
