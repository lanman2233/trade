package com.trade.quant.backtest;

import java.math.BigDecimal;
import java.util.List;

public class BacktestResult {

    private final BigDecimal totalReturn;
    private final BigDecimal annualizedReturn;
    private final BigDecimal maxDrawdown;
    private final BigDecimal sharpeRatio;
    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final BigDecimal winRate;
    private final BigDecimal profitFactor;
    private final BigDecimal avgWin;
    private final BigDecimal avgLoss;
    private final BigDecimal largestWin;
    private final BigDecimal largestLoss;
    private final BigDecimal expectancy;
    private final BigDecimal feeImpact;
    private final BigDecimal feeImpactPercent;
    private final List<BigDecimal> equityCurve;

    public BacktestResult(BigDecimal totalReturn,
                          BigDecimal annualizedReturn,
                          BigDecimal maxDrawdown,
                          BigDecimal sharpeRatio,
                          int totalTrades,
                          int winningTrades,
                          int losingTrades,
                          BigDecimal winRate,
                          BigDecimal profitFactor,
                          BigDecimal avgWin,
                          BigDecimal avgLoss,
                          BigDecimal largestWin,
                          BigDecimal largestLoss,
                          BigDecimal expectancy,
                          BigDecimal feeImpact,
                          BigDecimal feeImpactPercent,
                          List<BigDecimal> equityCurve) {
        this.totalReturn = totalReturn;
        this.annualizedReturn = annualizedReturn;
        this.maxDrawdown = maxDrawdown;
        this.sharpeRatio = sharpeRatio;
        this.totalTrades = totalTrades;
        this.winningTrades = winningTrades;
        this.losingTrades = losingTrades;
        this.winRate = winRate;
        this.profitFactor = profitFactor;
        this.avgWin = avgWin;
        this.avgLoss = avgLoss;
        this.largestWin = largestWin;
        this.largestLoss = largestLoss;
        this.expectancy = expectancy;
        this.feeImpact = feeImpact;
        this.feeImpactPercent = feeImpactPercent;
        this.equityCurve = equityCurve;
    }

    public BigDecimal getTotalReturn() { return totalReturn; }
    public BigDecimal getAnnualizedReturn() { return annualizedReturn; }
    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public BigDecimal getSharpeRatio() { return sharpeRatio; }
    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public int getLosingTrades() { return losingTrades; }
    public BigDecimal getWinRate() { return winRate; }
    public BigDecimal getProfitFactor() { return profitFactor; }
    public BigDecimal getAvgWin() { return avgWin; }
    public BigDecimal getAvgLoss() { return avgLoss; }
    public BigDecimal getLargestWin() { return largestWin; }
    public BigDecimal getLargestLoss() { return largestLoss; }
    public BigDecimal getExpectancy() { return expectancy; }
    public BigDecimal getFeeImpact() { return feeImpact; }
    public BigDecimal getFeeImpactPercent() { return feeImpactPercent; }
    public List<BigDecimal> getEquityCurve() { return equityCurve; }

    @Override
    public String toString() {
        return String.format(
                """
                ==================== 回测结果 ====================
                总收益率:           %.2f%%
                年化收益率:         %.2f%%
                最大回撤:           %.2f%%
                夏普比率:           %.2f

                交易统计:
                  总交易数:         %d
                  盈利交易数:       %d
                  亏损交易数:       %d
                  胜率:             %.2f%%
                  盈亏比:           %.2f

                盈亏统计:
                  平均盈利:         %.2f USDT
                  平均亏损:         %.2f USDT
                  单笔最大盈利:     %.2f USDT
                  单笔最大亏损:     %.2f USDT
                  期望值:           %.2f USDT

                手续费:
                  总手续费:         %.2f USDT
                  手续费影响:       %.2f%%
                =================================================
                """,
                totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
                totalTrades, winningTrades, losingTrades, winRate, profitFactor,
                avgWin, avgLoss, largestWin, largestLoss, expectancy,
                feeImpact, feeImpactPercent
        );
    }
}
