package com.trade.quant.backtest;

import com.trade.quant.core.Interval;
import com.trade.quant.core.KLine;
import com.trade.quant.core.Symbol;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class CsvKLineLoader {

    private CsvKLineLoader() {
    }

    public static List<KLine> load(Path path, Symbol symbol, Interval interval) throws IOException {
        List<KLine> kLines = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine(); // header
            if (line == null) {
                return kLines;
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 9) {
                    continue;
                }
                Instant openTime = Instant.parse(parts[0]);
                Instant closeTime = Instant.parse(parts[1]);
                BigDecimal open = new BigDecimal(parts[2]);
                BigDecimal high = new BigDecimal(parts[3]);
                BigDecimal low = new BigDecimal(parts[4]);
                BigDecimal close = new BigDecimal(parts[5]);
                BigDecimal volume = new BigDecimal(parts[6]);
                BigDecimal quoteVolume = new BigDecimal(parts[7]);
                long trades = Long.parseLong(parts[8]);

                kLines.add(new KLine(
                        symbol,
                        interval,
                        openTime,
                        closeTime,
                        open,
                        high,
                        low,
                        close,
                        volume,
                        quoteVolume,
                        trades
                ));
            }
        }

        return kLines;
    }
}
