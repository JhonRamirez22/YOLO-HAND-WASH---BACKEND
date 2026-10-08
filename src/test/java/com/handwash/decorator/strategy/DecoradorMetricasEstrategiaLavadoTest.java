package com.handwash.decorator.strategy;

import com.handwash.model.PasoLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.DomesticoStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecoradorMetricasEstrategiaLavadoTest {
    @Test
    void recordsRuleOutcomeWhilePreservingWrappedWashCriteria() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);
        var wrapped = new DecoradorMetricasEstrategiaLavado(
            new DomesticoStrategy(), TipoProtocolo.DOMESTICO, metrics);

        assertFalse(wrapped.validarTiempoPaso(PasoLavado.PASO_1_PALMAS, 5_999L));
        assertTrue(wrapped.validarTiempoPaso(PasoLavado.PASO_1_PALMAS, 6_000L));
        assertEquals(6_000L, wrapped.getTiempoRequeridoPaso(PasoLavado.PASO_1_PALMAS));
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
