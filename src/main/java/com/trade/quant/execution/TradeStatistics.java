package com.trade.quant.execution;

import java.math.BigDecimal;

/**
 * 交易统计
 */
public class TradeStatistics {

    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final BigDecimal winRate;
    private final BigDecimal totalPnL;
    private final BigDecimal avgPnL;
    private final BigDecimal largestWin;
    private final BigDecimal largestLoss;
    private final BigDecimal profitFactor;

    public TradeStatistics(int totalTrades, int winningTrades, int losingTrades,
                          BigDecimal winRate, BigDecimal totalPnL, BigDecimal avgPnL,
                          BigDecimal largestWin, BigDecimal largestLoss, BigDecimal profitFactor) {
        this.totalTrades = totalTrades;
        this.winningTrades = winningTrades;
        this.losingTrades = losingTrades;
        this.winRate = winRate;
        this.totalPnL = totalPnL;
        this.avgPnL = avgPnL;
        this.largestWin = largestWin;
        this.largestLoss = largestLoss;
        this.profitFactor = profitFactor;
    }

    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public int getLosingTrades() { return losingTrades; }
    public BigDecimal getWinRate() { return winRate; }
    public BigDecimal getTotalPnL() { return totalPnL; }
    public BigDecimal getAvgPnL() { return avgPnL; }
    public BigDecimal getLargestWin() { return largestWin; }
    public BigDecimal getLargestLoss() { return largestLoss; }
    public BigDecimal getProfitFactor() { return profitFactor; }

    @Override
    public String toString() {
        return String.format(
                """
                ==================== 交易统计 ====================
                总交易次数:        %d
                盈利次数:          %d
                亏损次数:          %d
                胜率:              %.2f%%
                总盈亏:            %.2f USDT
                平均盈亏:          %.2f USDT
                最大盈利:          %.2f USDT
                最大亏损:          %.2f USDT
                盈亏比:            %.2f
                ================================================
                """,
                totalTrades, winningTrades, losingTrades, winRate,
                totalPnL, avgPnL, largestWin, largestLoss, profitFactor
        );
    }
}
