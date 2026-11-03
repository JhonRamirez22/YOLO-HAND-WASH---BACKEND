package com.handwash.decorator.strategy;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolType;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.DomesticStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsWashStrategyDecoratorTest {
    @Test
    void recordsRuleOutcomeWhilePreservingWrappedWashCriteria() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);
        var wrapped = new MetricsWashStrategyDecorator(
            new DomesticStrategy(), ProtocolType.DOMESTICO, metrics);

        assertFalse(wrapped.validarTiempoPaso(WashStep.PASO_1_PALMAS, 5_999L));
        assertTrue(wrapped.validarTiempoPaso(WashStep.PASO_1_PALMAS, 6_000L));
        assertEquals(6_000L, wrapped.getTiempoRequeridoPaso(WashStep.PASO_1_PALMAS));
        assertFalse(wrapped.procedimientoCompletoValidado());
        assertEquals(1.0, registry.counter("handwash.detection.rule.decisions",
            "protocol", "DOMESTICO", "result", "rejected").count());
        assertEquals(1.0, registry.counter("handwash.detection.rule.decisions",
            "protocol", "DOMESTICO", "result", "accepted").count());
        assertEquals(1.0, registry.counter("handwash.detection.rule.step.decisions",
            "protocol", "DOMESTICO", "step", "PASO_1_PALMAS", "result", "rejected").count());
        assertEquals(1.0, registry.counter("handwash.detection.rule.step.decisions",
            "protocol", "DOMESTICO", "step", "PASO_1_PALMAS", "result", "accepted").count());
        assertEquals(0.0, registry.counter("handwash.detection.rule.step.decisions",
            "protocol", "DOMESTICO", "step", "PASO_2_DORSOS", "result", "accepted").count());
        registry.close();
    }
}
