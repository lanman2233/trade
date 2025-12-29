package com.trade.quant.strategy;

import com.trade.quant.core.*;

import java.util.List;

/**
 * 策略接口
 * 所有策略必须实现此接口
 *
 * 职责：
 * 1. 分析市场数据
 * 2. 产出交易信号
 * 3. 管理策略状态
 */
public interface Strategy {

    /**
     * 获取策略ID
     */
    String getStrategyId();

    /**
     * 获取策略名称
     */
    String getName();

    /**
     * 获取交易对
     */
    Symbol getSymbol();

    /**
     * 获取K线周期
     */
    Interval getInterval();

    /**
     * 分析市场数据并产生信号
     * @param kLines K线数据（按时间正序）
     * @return 交易信号，无信号返回null
     */
    Signal analyze(List<KLine> kLines);

    /**
     * 更新持仓状态（用于出场决策）
     * @param position 当前持仓
     * @param currentKLine 最新K线
     * @return 出场信号，无信号返回null
     */
    default Signal onPositionUpdate(Position position, KLine currentKLine) {
        return null;
    }

    /**
     * 初始化策略
     */
    default void initialize() {
        // 默认空实现
    }

    /**
     * 检查是否处于冷却期
     * @return true表示在冷却期，不应交易
     */
    default boolean isInCooldown() {
        return false;
    }

    /**
     * 获取冷却剩余时间（毫秒）
     */
    default long getCooldownRemaining() {
        return 0;
    }

    /**
     * 重置策略状态
     */
    default void reset() {
        // 默认空实现
    }

    /**
     * 获取策略配置
     */
    StrategyConfig getConfig();

    /**
     * 设置策略配置
     */
    void setConfig(StrategyConfig config);
}
