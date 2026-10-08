package com.handwash.agent;

import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingStep;
import com.handwash.model.HandwashingSession;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolPipelineTest {
    private static final HandwashingStep[] PASOS = {
        HandwashingStep.PASO_1_PALMAS, HandwashingStep.PASO_2_DORSOS,
        HandwashingStep.PASO_3_INTERDIGITALES, HandwashingStep.PASO_4_NUDILLOS,
        HandwashingStep.PASO_5_PULGAR, HandwashingStep.PASO_6_PUNTA_DE_DEDOS,
        HandwashingStep.PASO_7_CIRCULARES
    };

    @Test
    void correctlyTimedFrictionSequenceIsNotPresentedAsCompleteWhoProcedure() {
        Fixture fixture = new Fixture();
        long time = 1_000L;
        for (int index = 0; index < PASOS.length; index++) {
            HandwashingStep paso = PASOS[index];
            fixture.detect(paso, time);
            if (index > 0) {
                time += 300L;
                fixture.detect(paso, time);
            }
            long required = fixture.session.getEstrategia().getTiempoRequeridoPaso(paso);
            for (long elapsed = 1_000L; elapsed <= required; elapsed += 1_000L) {
                time += 1_000L;
                fixture.detect(paso, time);
            }
            time += 400L;
        }

        Map<String, Object> summary = fixture.summary();
        assertEquals("SECUENCIA_COMPLETADA_SIN_VALIDAR_PROCEDIMIENTO_COMPLETO", summary.get("resultado"));
        assertEquals(false, summary.get("aprobado"));
        assertEquals(false, summary.get("procedimientoCompletoValidado"));
        assertTrue(((java.util.List<?>) summary.get("accionesNoDetectadas")).contains("APLICAR_JABON"));
        assertTrue(fixture.session.getHistorialInfracciones().isEmpty());
    }

    @Test
    void shortStepIsReportedAndSequenceContinuesWithoutRestart() {
        Fixture fixture = new Fixture();
        fixture.detect(HandwashingStep.PASO_1_PALMAS, 1_000L);
        fixture.detect(HandwashingStep.PASO_1_PALMAS, 2_000L);
        fixture.detect(HandwashingStep.PASO_2_DORSOS, 3_000L); // Palmas: 2s, below 6s
        fixture.detect(HandwashingStep.PASO_2_DORSOS, 3_300L);

        Map<String, Object> summary = fixture.summary();
        assertEquals(false, summary.get("aprobado"));
        assertEquals("EN_PROGRESO", fixture.session.getEstadoSesion().name());
        assertEquals(HandwashingStep.PASO_2_DORSOS, fixture.session.getEstadoActual().getPasoActual());
        assertEquals(0, fixture.session.getIntentosReiniciados());
        assertEquals(0, ((java.util.List<?>) summary.get("historialIntentos")).size());
        assertFalse(fixture.session.getHistorialInfracciones().isEmpty());
        assertEquals("INCOMPLETO", summary.get("resultado"));
    }

    @Test
    void cleanRetryIsEvaluatedSeparatelyFromTheFailedAttempt() {
        Fixture fixture = new Fixture();
        fixture.detect(HandwashingStep.PASO_1_PALMAS, 1_000L);
        fixture.detect(HandwashingStep.PASO_3_INTERDIGITALES, 2_000L); // skipped step; caches attempt 1
        fixture.detect(HandwashingStep.PASO_3_INTERDIGITALES, 2_300L); // second vote confirms the skip

        long time = 100_000L;
        for (int index = 0; index < PASOS.length; index++) {
            HandwashingStep paso = PASOS[index];
            fixture.detect(paso, time);
            if (index > 0) {
                time += 300L;
                fixture.detect(paso, time);
            }
            long required = fixture.session.getEstrategia().getTiempoRequeridoPaso(paso);
            for (long elapsed = 1_000L; elapsed <= required; elapsed += 1_000L) {
                time += 1_000L;
                fixture.detect(paso, time);
            }
            time += 400L;
        }

        Map<String, Object> summary = fixture.summary();
        assertEquals("SECUENCIA_COMPLETADA_SIN_VALIDAR_PROCEDIMIENTO_COMPLETO", summary.get("resultado"));
        assertEquals(false, summary.get("aprobado"));
        assertEquals(true, summary.get("intentoFinalSinInfracciones"));
        assertEquals(1, summary.get("intentosReiniciados"));
        assertEquals(1, ((java.util.List<?>) summary.get("historialIntentos")).size());
        assertTrue(fixture.session.getInfraccionesIntentoActual().isEmpty());
        assertFalse(fixture.session.getHistorialInfracciones().isEmpty());
    }

    private static class Fixture {
        final SessionManager manager = new SessionManager(new Receiver());
        final String id = manager.crearSesion(ProtocolType.DOMESTICO);
        final HandwashingSession session = manager.getSesion(id);
        final RuleValidator validator = new RuleValidator(manager);
        final Notifier notifier = new Notifier(manager);

        void detect(HandwashingStep step, long timestamp) {
            session.procesarDeteccion(step, timestamp);
            validator.onDeteccion(new DetectionEvent(id, step.getClaseModelo(), 0.9f,
                Instant.now().toString()));
        }

        Map<String, Object> summary() {
            return notifier.crearResumen(id, session);
        }
    }
}
