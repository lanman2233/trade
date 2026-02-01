package com.trade.quant.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KLineAggregatorTest {

    @Test
    void testAggregate15mTo1h() {
        Symbol symbol = Symbol.of("BTC-USDT");
        List<KLine> input = new ArrayList<>();

        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 8; i++) {
            Instant openTime = start.plusSeconds(900L * i);
            Instant closeTime = openTime.plusSeconds(900L);
            BigDecimal open = BigDecimal.valueOf(100 + i);
            BigDecimal high = BigDecimal.valueOf(110 + i);
            BigDecimal low = BigDecimal.valueOf(90 + i);
            BigDecimal close = BigDecimal.valueOf(105 + i);
            BigDecimal volume = BigDecimal.valueOf(10);
            BigDecimal quoteVolume = BigDecimal.valueOf(1000);
            input.add(new KLine(
                    symbol,
                    Interval.FIFTEEN_MINUTES,
                    openTime,
                    closeTime,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    quoteVolume,
                    1
            ));
        }

        List<KLine> hourly = KLineAggregator.aggregate(input, Interval.FIFTEEN_MINUTES, Interval.ONE_HOUR);
        assertEquals(2, hourly.size());

        KLine first = hourly.get(0);
        assertEquals(0, first.getOpen().compareTo(BigDecimal.valueOf(100)));
        assertEquals(0, first.getClose().compareTo(BigDecimal.valueOf(108)));
        assertEquals(0, first.getHigh().compareTo(BigDecimal.valueOf(113)));
        assertEquals(0, first.getLow().compareTo(BigDecimal.valueOf(90)));
    }
}
