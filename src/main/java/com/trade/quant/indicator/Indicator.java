package com.trade.quant.indicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * 技术指标接口
 */
public interface Indicator {

    /**
     * 计算指标值
     * @param prices 价格序列
     * @return 指标值序列
     */
    List<BigDecimal> calculate(List<BigDecimal> prices);

    /**
     * 获取指标名称
     */
    String getName();
}
