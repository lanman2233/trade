package com.trade.quant.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * 回测结果
 */
public class BacktestResult {

    private final BigDecimal totalReturn;        // 总收益率
    private final BigDecimal annualizedReturn;   // 年化收益率
    private final BigDecimal maxDrawdown;        // 最大回撤
    private final BigDecimal sharpeRatio;        // 夏普比率
    private final int totalTrades;               // 总交易次数
    private final int winningTrades;             // 盈利次数
    private final int losingTrades;              // 亏损次数
    private final BigDecimal winRate;            // 胜率
    private final BigDecimal profitFactor;       // 盈亏比
    private final BigDecimal avgWin;             // 平均盈利
    private final BigDecimal avgLoss;            // 平均亏损
    private final BigDecimal largestWin;         // 最大盈利
    private final BigDecimal largestLoss;        // 最大亏损
    private final List<BigDecimal> equityCurve;  // 资金曲线

    public BacktestResult(BigDecimal totalReturn, BigDecimal annualizedReturn,
                        BigDecimal maxDrawdown, BigDecimal sharpeRatio,
                        int totalTrades, int winningTrades, int losingTrades,
                        BigDecimal winRate, BigDecimal profitFactor,
                        BigDecimal avgWin, BigDecimal avgLoss,
                        BigDecimal largestWin, BigDecimal largestLoss,
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
    public List<BigDecimal> getEquityCurve() { return equityCurve; }

    @Override
    public String toString() {
        return String.format(
                """
                ==================== 回测结果 ====================
                总收益率:        %.2f%%
                年化收益率:      %.2f%%
                最大回撤:        %.2f%%
                夏普比率:        %.2f

                交易统计:
                  总交易次数:    %d
                  盈利次数:      %d
                  亏损次数:      %d
                  胜率:          %.2f%%
                  盈亏比:        %.2f

                盈亏分析:
                  平均盈利:      %.2f USDT
                  平均亏损:      %.2f USDT
                  最大盈利:      %.2f USDT
                  最大亏损:      %.2f USDT
                ================================================
                """,
                totalReturn, annualizedReturn, maxDrawdown, sharpeRatio,
                totalTrades, winningTrades, losingTrades, winRate, profitFactor,
                avgWin, avgLoss, largestWin, largestLoss
        );
    }
}
