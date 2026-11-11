package com.handwash.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandwashIntentMetricsTest {
    @Test
    void recordsFiniteIntentRejectionCategoriesWithoutUsingArbitraryTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new HandwashMetrics(registry);

        metrics.recordIntentRejection("ENCUADRE_INCOMPLETO");
        metrics.recordIntentRejection("MOVIMIENTO_INICIAL_NO_DETECTADO");
        metrics.recordIntentRejection("MOVIMIENTO_ACTIVO_NO_DETECTADO");
        metrics.recordIntentRejection("session-uuid-or-unrecognized-text");
        metrics.recordIntentRejection(null);

        assertEquals(1.0, registry.counter("handwash.detection.intent.rejections",
            "reason", "encuadre_incompleto").count());
        assertEquals(1.0, registry.counter("handwash.detection.intent.rejections",
            "reason", "movimiento_inicial_no_detectado").count());
        assertEquals(1.0, registry.counter("handwash.detection.intent.rejections",
            "reason", "movimiento_activo_no_detectado").count());
        assertEquals(1.0, registry.counter("handwash.detection.intent.rejections",
            "reason", "otro").count());
        assertNull(registry.find("handwash.detection.intent.rejections")
            .tag("reason", "session-uuid-or-unrecognized-text").counter());
        assertNotNull(registry.find("handwash.detection.intent.rejections")
            .tag("reason", "sin_evidencia_reciente").counter());
    }
}
