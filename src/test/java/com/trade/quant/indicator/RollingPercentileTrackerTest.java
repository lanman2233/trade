package com.trade.quant.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RollingPercentileTrackerTest {

    @Test
    void testPercentileCalculation() {
        RollingPercentileTracker tracker = new RollingPercentileTracker(10);
        tracker.update(BigDecimal.ONE);
        tracker.update(BigDecimal.valueOf(3));
        tracker.update(BigDecimal.valueOf(2));

        BigDecimal percentile = tracker.getPercentile();
        assertEquals(0, percentile.compareTo(new BigDecimal("0.6667")));
    }

    @Test
    void testHasEnoughSamples() {
        RollingPercentileTracker tracker = new RollingPercentileTracker(10);
        for (int i = 0; i < 8; i++) {
            tracker.update(BigDecimal.valueOf(i + 1));
        }
        assertTrue(tracker.hasEnoughSamples());
    }
}
