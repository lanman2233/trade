package com.trade.quant.backtest;

import com.trade.quant.core.PositionSide;
import com.trade.quant.core.Symbol;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 已平仓交易记录
 * 用于回测统计，包含完整的入场和出场信息
 */
public class ClosedTrade {
    private final String tradeId;
    private final Symbol symbol;
    private final PositionSide side;
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final BigDecimal quantity;
    private final BigDecimal pnl;           // 盈亏（不含手续费）
    private final BigDecimal fee;           // 手续费
    private final BigDecimal netPnl;        // 净盈亏（含手续费）
    private final Instant entryTime;
    private final Instant exitTime;
    private final String strategyId;

    public ClosedTrade(String tradeId, Symbol symbol, PositionSide side,
                       BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity,
                       BigDecimal pnl, BigDecimal fee, Instant entryTime, Instant exitTime,
                       String strategyId) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.quantity = quantity;
        this.pnl = pnl;
        this.fee = fee;
        this.netPnl = pnl.subtract(fee);
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.strategyId = strategyId;
    }

    public String getTradeId() { return tradeId; }
    public Symbol getSymbol() { return symbol; }
    public PositionSide getSide() { return side; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPnl() { return pnl; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getNetPnl() { return netPnl; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public String getStrategyId() { return strategyId; }

    /**
     * 判断是否盈利
     */
    public boolean isWin() {
        return netPnl.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 判断是否亏损
     */
    public boolean isLoss() {
        return netPnl.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 计算收益率（百分比）
     */
    public BigDecimal getReturnPercent() {
        BigDecimal entryValue = entryPrice.multiply(quantity);
        if (entryValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return netPnl.divide(entryValue, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    @Override
    public String toString() {
        return String.format("ClosedTrade{id=%s, symbol=%s, side=%s, entry=%s, exit=%s, qty=%s, pnl=%s, netPnl=%s}",
                tradeId, symbol, side, entryPrice, exitPrice, quantity, pnl, netPnl);
    }
}
