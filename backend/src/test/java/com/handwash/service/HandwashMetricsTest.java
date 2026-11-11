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

    @Test
    void openCvResidualMetricIsDiagnosticBoundedAndIgnoresInvalidValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);

        metrics.recordOpenCvMotionResidual(0.25);
        metrics.recordOpenCvMotionResidual(100.0);
        metrics.recordOpenCvMotionResidual(Double.NaN);
        metrics.recordOpenCvMotionResidual(-1.0);

        var summary = registry.get("handwash.opencv.motion.residual").summary();
        assertEquals(2L, summary.count());
        assertEquals(10.25, summary.totalAmount(), 1e-9,
            "outliers must be bounded so a corrupt diagnostic cannot poison percentiles");
    }
}
