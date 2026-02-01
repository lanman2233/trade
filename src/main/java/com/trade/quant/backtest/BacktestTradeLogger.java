package com.trade.quant.backtest;

import com.trade.quant.strategy.ExitReason;
import com.trade.quant.strategy.TradeMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 回测交易日志（CSV）
 */
public class BacktestTradeLogger {

    private final Path filePath;

    public BacktestTradeLogger(String filePath) {
        this.filePath = Paths.get(filePath);
        initFile();
    }

    public void record(ClosedTrade trade, ExitReason reason, TradeMetrics metrics) {
        String line = String.format(
                "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                trade.getEntryTime(),
                trade.getExitTime(),
                trade.getStrategyId(),
                trade.getSymbol(),
                trade.getSide(),
                trade.getEntryPrice(),
                trade.getExitPrice(),
                trade.getQuantity(),
                trade.getPnl(),
                trade.getFee(),
                trade.getNetPnl(),
                valueOrEmpty(metrics == null ? null : metrics.atrPct()),
                valueOrEmpty(metrics == null ? null : metrics.rsi()),
                valueOrEmpty(metrics == null ? null : metrics.ema20()),
                valueOrEmpty(metrics == null ? null : metrics.ema200()),
                reason == null ? "" : reason.name()
        );

        try {
            Files.writeString(filePath, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("写入回测交易日志失败: " + e.getMessage());
        }
    }

    private void initFile() {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            if (!Files.exists(filePath)) {
                Files.writeString(filePath,
                        "entry_time,exit_time,strategy_id,symbol,side,entry_price,exit_price,quantity,pnl,fee,net_pnl,atr_pct,rsi,ema20,ema200,exit_reason\n",
                        StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new RuntimeException("初始化回测交易日志失败: " + e.getMessage(), e);
        }
    }

    private String valueOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }
}
