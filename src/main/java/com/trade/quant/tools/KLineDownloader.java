package com.trade.quant.tools;

import com.trade.quant.core.ConfigManager;
import com.trade.quant.core.Interval;
import com.trade.quant.core.KLine;
import com.trade.quant.core.Symbol;
import com.trade.quant.exchange.Exchange;
import com.trade.quant.exchange.ExchangeFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class KLineDownloader {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(365, ChronoUnit.DAYS);

        if (args.length >= 2) {
            startTime = Instant.parse(args[0]);
            endTime = Instant.parse(args[1]);
        }

        Symbol symbol = Symbol.of("BTC-USDT");
        Interval interval = Interval.FIFTEEN_MINUTES;

        Exchange exchange = buildExchange();
        List<KLine> kLines = fetchKLines(exchange, symbol, interval, startTime, endTime);
        Path outFile = writeCsv(symbol, interval, startTime, endTime, kLines);

        System.out.println("Saved " + kLines.size() + " klines to " + outFile.toAbsolutePath());
    }

    private static Exchange buildExchange() {
        ConfigManager configManager = ConfigManager.getInstance();
        Exchange exchange = ExchangeFactory.createBinance();
        exchange.setApiKey(
                configManager.getBinanceApiKey(),
                configManager.getBinanceApiSecret(),
                null
        );

        if (configManager.isProxyEnabled()) {
            exchange.setProxy(configManager.getProxyHost(), configManager.getProxyPort());
        }
        return exchange;
    }

    private static List<KLine> fetchKLines(Exchange exchange,
                                           Symbol symbol,
                                           Interval interval,
                                           Instant startTime,
                                           Instant endTime) throws Exception {
        List<KLine> allKLines = new ArrayList<>();
        long endMillis = endTime.toEpochMilli();
        long startMillis = startTime.toEpochMilli();

        while (endMillis > startMillis) {
            List<KLine> batch = exchange.getKLines(symbol, interval, 1000, endMillis);
            if (batch.isEmpty()) {
                break;
            }
            allKLines.addAll(0, batch);
            endMillis = batch.get(0).getOpenTime().toEpochMilli() - 1;
        }

        return allKLines.stream()
                .filter(k -> !k.getOpenTime().isBefore(startTime))
                .filter(k -> !k.getCloseTime().isAfter(endTime))
                .toList();
    }

    private static Path writeCsv(Symbol symbol,
                                 Interval interval,
                                 Instant startTime,
                                 Instant endTime,
                                 List<KLine> kLines) throws IOException {
        Path dir = Paths.get("data", "klines");
        Files.createDirectories(dir);

        String fileName = symbol.toPairString()
                + "-" + interval.getCode()
                + "-" + FILE_DATE.format(startTime)
                + "-" + FILE_DATE.format(endTime)
                + ".csv";
        Path filePath = dir.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("open_time,close_time,open,high,low,close,volume,quote_volume,trades");
            writer.newLine();
            for (KLine k : kLines) {
                writer.write(k.getOpenTime() + "," +
                        k.getCloseTime() + "," +
                        k.getOpen() + "," +
                        k.getHigh() + "," +
                        k.getLow() + "," +
                        k.getClose() + "," +
                        k.getVolume() + "," +
                        k.getQuoteVolume() + "," +
                        k.getTrades());
                writer.newLine();
            }
        }

        return filePath;
    }
}
