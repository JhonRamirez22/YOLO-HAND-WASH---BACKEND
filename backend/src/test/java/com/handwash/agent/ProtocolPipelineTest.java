package com.handwash.agent;

import com.handwash.model.DetectionEvent;
import com.handwash.model.WashStep;
import com.handwash.model.WashSession;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolPipelineTest {
    private static final WashStep[] PASOS = {
        WashStep.PASO_1_PALMAS, WashStep.PASO_2_DORSOS,
        WashStep.PASO_3_INTERDIGITALES, WashStep.PASO_4_NUDILLOS,
        WashStep.PASO_5_PULGAR, WashStep.PASO_6_PUNTA_DE_DEDOS
    };

    @Test
    void sixFrictionMovementsRemainInformationalAndDoNotCompleteWhoProcedure() {
        Fixture fixture = new Fixture();
        long time = 1_000L;
        for (int index = 0; index < PASOS.length; index++) {
            WashStep paso = PASOS[index];
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
        assertEquals("INCOMPLETO", summary.get("resultado"));
        assertEquals("EN_PROGRESO", summary.get("estadoSesion"));
        assertEquals(6, fixture.session.getPasosCompletados());
        assertTrue(fixture.session.isSecuenciaFriccionObservadaCompleta());
        assertTrue(((java.util.List<?>) summary.get("pasosFaltantes")).isEmpty());
        assertEquals(true, summary.get("secuenciaFriccionObservadaCompleta"));
        assertEquals(false, summary.get("aprobado"));
        assertEquals(false, summary.get("procedimientoCompletoValidado"));
        assertTrue(((java.util.List<?>) summary.get("accionesNoDetectadas")).contains("APLICAR_JABON"));
        assertTrue(fixture.session.getHistorialInfracciones().isEmpty());
    }

    @Test
    void shortStepIsReportedAndSequenceContinuesWithoutRestart() {
        Fixture fixture = new Fixture();
        fixture.detect(WashStep.PASO_1_PALMAS, 1_000L);
        fixture.detect(WashStep.PASO_1_PALMAS, 2_000L);
        fixture.detect(WashStep.PASO_2_DORSOS, 3_000L); // Palmas: 2s, below 6s
        fixture.detect(WashStep.PASO_2_DORSOS, 3_300L);

        Map<String, Object> summary = fixture.summary();
        assertEquals(false, summary.get("aprobado"));
        assertEquals("EN_PROGRESO", fixture.session.getEstadoSesion().name());
        assertEquals(WashStep.PASO_2_DORSOS, fixture.session.getEstadoActual().getPasoActual());
        assertEquals(0, fixture.session.getIntentosReiniciados());
        assertEquals(0, ((java.util.List<?>) summary.get("historialIntentos")).size());
        assertFalse(fixture.session.getHistorialInfracciones().isEmpty());
        assertEquals("INCOMPLETO", summary.get("resultado"));
    }

    @Test
    void cleanRetryIsEvaluatedSeparatelyFromTheFailedAttempt() {
        Fixture fixture = new Fixture();
        fixture.detect(WashStep.PASO_1_PALMAS, 1_000L);
        fixture.detect(WashStep.PASO_3_INTERDIGITALES, 2_000L); // skipped step; caches attempt 1
        fixture.detect(WashStep.PASO_3_INTERDIGITALES, 2_300L); // second vote confirms the skip

        long time = 100_000L;
        for (int index = 0; index < PASOS.length; index++) {
            WashStep paso = PASOS[index];
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
        assertEquals("INCOMPLETO", summary.get("resultado"));
        assertEquals("EN_PROGRESO", summary.get("estadoSesion"));
        assertTrue(fixture.session.isSecuenciaFriccionObservadaCompleta());
        assertEquals(false, summary.get("aprobado"));
        assertEquals(true, summary.get("intentoFinalSinInfracciones"));
        assertEquals(1, summary.get("intentosReiniciados"));
        assertEquals(1, ((java.util.List<?>) summary.get("historialIntentos")).size());
        assertTrue(fixture.session.getInfraccionesIntentoActual().isEmpty());
        assertFalse(fixture.session.getHistorialInfracciones().isEmpty());
    }

    private static class Fixture {
        final SessionManager manager = new SessionManager(new DetectionReceiver());
        final String id = manager.crearSesion(ProtocolType.DOMESTICO);
        final WashSession session = manager.getSesion(id);
        final RuleValidator validator = new RuleValidator(manager);
        final Notifier notifier = new Notifier(manager);

        void detect(WashStep step, long timestamp) {
            session.procesarDeteccion(step, timestamp);
            validator.onDeteccion(new DetectionEvent(id, step.getClaseModelo(), 0.9f,
                Instant.now().toString()));
        }

        Map<String, Object> summary() {
            return notifier.crearResumen(id, session);
        }
    }
}
