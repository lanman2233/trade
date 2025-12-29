package com.trade.quant.execution;

import com.trade.quant.core.Trade;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV交易记录实现
 */
public class CsvTradeRecorder implements TradeRecorder {

    private final String filePath;
    private final DateTimeFormatter dateFormat;

    public CsvTradeRecorder(String filePath) {
        this.filePath = filePath;
        this.dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // 创建文件头
        if (!new File(filePath).exists()) {
            try {
                Files.writeString(Paths.get(filePath),
                        "时间,交易ID,订单ID,策略,交易对,方向,价格,数量,手续费,价值\n",
                        StandardOpenOption.CREATE);
            } catch (IOException e) {
                throw new RuntimeException("创建交易记录文件失败", e);
            }
        }
    }

    @Override
    public void record(Trade trade) {
        String line = String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                LocalDateTime.now().format(dateFormat),
                trade.getTradeId(),
                trade.getOrderId(),
                trade.getStrategyId(),
                trade.getSymbol(),
                trade.getSide(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getFee(),
                trade.getValue()
        );

        try {
            Files.writeString(Paths.get(filePath), line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("写入交易记录失败: " + e.getMessage());
        }
    }

    @Override
    public List<Trade> getAllTrades() {
        return readTradesFromFile();
    }

    @Override
    public List<Trade> getTradesByStrategy(String strategyId) {
        List<Trade> all = readTradesFromFile();
        return all.stream()
                .filter(t -> t.getStrategyId().equals(strategyId))
                .toList();
    }

    @Override
    public List<Trade> getTradesByTimeRange(long startTime, long endTime) {
        List<Trade> all = readTradesFromFile();
        return all.stream()
                .filter(t -> t.getTimestamp().toEpochMilli() >= startTime)
                .filter(t -> t.getTimestamp().toEpochMilli() <= endTime)
                .toList();
    }

    @Override
    public TradeStatistics getStatistics() {
        List<Trade> trades = readTradesFromFile();

        if (trades.isEmpty()) {
            return new TradeStatistics(0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // TODO: 计算盈亏需要入场价格对比，这里简化处理
        return new TradeStatistics(
                trades.size(),
                0, 0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    private List<Trade> readTradesFromFile() {
        List<Trade> trades = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            reader.readLine(); // 跳过标题行

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 10) {
                    // 简化解析
                    // 实际应该完整解析并创建Trade对象
                }
            }
        } catch (IOException e) {
            System.err.println("读取交易记录失败: " + e.getMessage());
        }

        return trades;
    }
}
