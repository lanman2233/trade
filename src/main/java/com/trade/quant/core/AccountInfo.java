package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账户信息
 */
public class AccountInfo {
    private final BigDecimal totalBalance;       // 总权益（USDT）
    private final BigDecimal availableBalance;   // 可用余额（USDT）
    private final BigDecimal unrealizedPnl;      // 未实现盈亏
    private final BigDecimal marginRatio;        // 保证金率
    private final Instant timestamp;             // 更新时间

    public AccountInfo(BigDecimal totalBalance, BigDecimal availableBalance,
                      BigDecimal unrealizedPnl, BigDecimal marginRatio) {
        this.totalBalance = totalBalance;
        this.availableBalance = availableBalance;
        this.unrealizedPnl = unrealizedPnl;
        this.marginRatio = marginRatio;
        this.timestamp = Instant.now();
    }

    public BigDecimal getTotalBalance() { return totalBalance; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public BigDecimal getMarginRatio() { return marginRatio; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * 计算账户收益率
     */
    public BigDecimal getReturnRate(BigDecimal initialBalance) {
        if (initialBalance == null || initialBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return Decimal.divide(unrealizedPnl, initialBalance)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 检查是否接近强平
     */
    public boolean isNearLiquidation(BigDecimal threshold) {
        return marginRatio.compareTo(threshold) < 0;
    }
}
