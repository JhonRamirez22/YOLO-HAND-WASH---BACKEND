package com.handwash.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandwashMetricsTest {
    @Test
    void producerCaptureAgeMetricBoundsUntrustedDiagnosticOutliers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);

        metrics.recordProducerCaptureAge(Long.MAX_VALUE);

        assertEquals(1L, registry.get("handwash.producer.capture.age").timer().count());
        assertEquals(TimeUnit.DAYS.toMillis(1),
            registry.get("handwash.producer.capture.age").timer().max(TimeUnit.MILLISECONDS));
    }

    @Test
    void producerCaptureAgeMetricPreservesNormalValuesAndClampsNegativeDiagnostics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);
        metrics.recordProducerCaptureAge(240L);
        metrics.recordProducerCaptureAge(-1L);

        var timer = registry.get("handwash.producer.capture.age").timer();
        assertEquals(2L, timer.count());
        assertEquals(240L, timer.totalTime(TimeUnit.MILLISECONDS));
        assertEquals(240L, timer.max(TimeUnit.MILLISECONDS));
    }
}
