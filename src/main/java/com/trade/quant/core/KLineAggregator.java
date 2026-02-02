package com.trade.quant.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K线数据聚合器
 */
public final class KLineAggregator {

    private KLineAggregator() {
    }

    /**
     * 将低时间周期K线聚合到高时间周期
     *
     * @param source         源K线数据
     * @param sourceInterval 源K线周期
     * @param targetInterval 目标K线周期
     * @return 聚合后K线数据
     */
    public static List<KLine> aggregate(List<KLine> source, Interval sourceInterval, Interval targetInterval) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (sourceInterval.getMinutes() >= targetInterval.getMinutes()) {
            throw new IllegalArgumentException("目标周期必须大于源周期");
        }
        if (targetInterval.getMinutes() % sourceInterval.getMinutes() != 0) {
            throw new IllegalArgumentException("目标周期必须是源周期的整数倍："
                    + sourceInterval.getMinutes() + " -> " + targetInterval.getMinutes());
        }

        long bucketMillis = targetInterval.getMinutes() * 60_000L;
        int barsPerBucket = targetInterval.getMinutes() / sourceInterval.getMinutes();

        Map<Long, List<KLine>> buckets = new LinkedHashMap<>();
        for (KLine kLine : source) {
            long openMillis = kLine.getOpenTime().toEpochMilli();
            long bucketStart = (openMillis / bucketMillis) * bucketMillis;
            buckets.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(kLine);
        }

        List<KLine> aggregated = new ArrayList<>();
        for (Map.Entry<Long, List<KLine>> entry : buckets.entrySet()) {
            List<KLine> bucket = entry.getValue();
            if (bucket.size() < barsPerBucket) {
                continue; // 数据不足，跳过当前分组
            }

            KLine first = bucket.get(0);
            KLine last = bucket.get(barsPerBucket - 1);

            BigDecimal open = first.getOpen();
            BigDecimal close = last.getClose();
            BigDecimal high = first.getHigh();
            BigDecimal low = first.getLow();
            BigDecimal volume = BigDecimal.ZERO;
            BigDecimal quoteVolume = BigDecimal.ZERO;
            long trades = 0;

            for (int i = 0; i < barsPerBucket; i++) {
                KLine bar = bucket.get(i);
                high = high.max(bar.getHigh());
                low = low.min(bar.getLow());
                volume = volume.add(bar.getVolume());
                quoteVolume = quoteVolume.add(bar.getQuoteVolume());
                trades += bar.getTrades();
            }

            Instant openTime = Instant.ofEpochMilli(entry.getKey());
            Instant closeTime = openTime.plusMillis(bucketMillis);
            aggregated.add(new KLine(
                    first.getSymbol(),
                    targetInterval,
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

        return aggregated;
    }
}
