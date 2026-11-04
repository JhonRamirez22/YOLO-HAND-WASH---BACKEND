package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WashSessionTest {

    @Test
    void fondoRestartsActiveAttemptAndKeepsOnlyErrorSummary() {
        WashSession sesion = new WashSession("s1", ProtocolType.CLINICO_QUIRURGICO);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);

        WashStateResponse response = sesion.procesarDeteccion(WashStep.FONDO, 2_000L);

        assertNull(response.getEstadoActual());
        assertEquals(Boolean.FALSE, response.getManoDetectada());
        assertEquals("ESPERANDO_INICIO", response.getEstadoSesion());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA, response.getInfraccion().getTipo());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            response.getUltimoErrorReinicio().getTipo());
        assertEquals(1, response.getIntentosReiniciados());
        assertEquals(1, sesion.getIntentosAnteriores().size());
    }

    @Test
    void inactivityExpiryArchivesAnActivePartialAttempt() {
        WashSession sesion = new WashSession("partial-expiry", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);

        sesion.expirar();

        assertEquals(SessionStatus.EXPIRADA, sesion.getEstadoSesion());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        WashAttemptSummary attempt = sesion.getIntentosAnteriores().get(0);
        assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD", attempt.motivoReinicio());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            attempt.infracciones().get(0).getTipo());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            sesion.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
    }

    @Test
    void partialStepBoxMarksHandsVisibleOnlyDuringAnActiveObservedAttempt() {
        WashSession sesion = new WashSession("visible", ProtocolType.DOMESTICO);
        assertEquals(Boolean.FALSE, sesion.getEstadoActualResponse().getManoDetectada());

        WashStateResponse observed = sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);
        assertEquals(Boolean.TRUE, observed.getManoDetectada());

        WashStateResponse hidden = sesion.procesarDeteccion(WashStep.FONDO, 1_200L);
        assertEquals(Boolean.FALSE, hidden.getManoDetectada());
    }

    @Test
    void sixFrictionMovementsAreInformationalAndDoNotCompleteTheHandwashSession() {
        WashSession sesion = new WashSession("friction-only", ProtocolType.DOMESTICO,
            new FastValidationStrategy());
        WashStep[] steps = {
            WashStep.PASO_1_PALMAS,
            WashStep.PASO_2_DORSOS,
            WashStep.PASO_3_INTERDIGITALES,
            WashStep.PASO_4_NUDILLOS,
            WashStep.PASO_5_PULGAR,
            WashStep.PASO_6_PUNTA_DE_DEDOS
        };
        long timestamp = 1_000L;
        sesion.procesarDeteccion(steps[0], timestamp);

        for (int index = 0; index < steps.length; index++) {
            WashStep step = steps[index];
            timestamp = accumulateStep(sesion, step, timestamp, 400L);
            if (index + 1 < steps.length) {
                timestamp += 100L;
                sesion.procesarDeteccion(steps[index + 1], timestamp);
                timestamp += 100L;
                sesion.procesarDeteccion(steps[index + 1], timestamp);
            }
        }

        WashStateResponse observed = sesion.getEstadoActualResponse();
        assertEquals(SessionStatus.EN_PROGRESO, sesion.getEstadoSesion());
        assertEquals(WashStep.PASO_6_PUNTA_DE_DEDOS, sesion.getEstadoActual().getPasoActual());
        assertEquals(6, observed.getProgreso().getPasosCompletados());
        assertEquals(6, observed.getProgreso().getPasosTotales());
        assertTrue(observed.isSecuenciaFriccionObservadaCompleta());
        assertFalse(observed.isProcedimientoCompletoValidado());
        assertEquals(WashStep.PASO_6_PUNTA_DE_DEDOS, sesion.getPasoPendienteValidacion());
        assertTrue(sesion.getTiempoPendienteValidacionMs() >= 400L);

        WashStateResponse rejectedStepSeven = sesion.procesarDeteccion(
            WashStep.PASO_7_CIRCULARES, timestamp + 100L);
        assertEquals(SessionStatus.EN_PROGRESO, sesion.getEstadoSesion());
        assertEquals(WashStep.PASO_6_PUNTA_DE_DEDOS, sesion.getEstadoActual().getPasoActual());
        assertEquals(6, rejectedStepSeven.getProgreso().getPasosCompletados());
        assertFalse(rejectedStepSeven.isProcedimientoCompletoValidado());
    }

    @Test
    void evidenceLossClearsTheInformationalSixMovementProgress() {
        WashSession sesion = new WashSession("friction-reset", ProtocolType.DOMESTICO,
            new FastValidationStrategy());
        long timestamp = completeSixFrictionMovements(sesion);
        assertTrue(sesion.isSecuenciaFriccionObservadaCompleta());

        WashStateResponse reset = sesion.procesarDeteccion(WashStep.FONDO, timestamp + 100L);

        assertEquals(SessionStatus.ESPERANDO_INICIO, sesion.getEstadoSesion());
        assertEquals(0, reset.getProgreso().getPasosCompletados());
        assertEquals(6, reset.getProgreso().getPasosTotales());
        assertFalse(reset.isSecuenciaFriccionObservadaCompleta());
        assertFalse(reset.isProcedimientoCompletoValidado());
    }

    private static long completeSixFrictionMovements(WashSession session) {
        WashStep[] steps = {
            WashStep.PASO_1_PALMAS,
            WashStep.PASO_2_DORSOS,
            WashStep.PASO_3_INTERDIGITALES,
            WashStep.PASO_4_NUDILLOS,
            WashStep.PASO_5_PULGAR,
            WashStep.PASO_6_PUNTA_DE_DEDOS
        };
        long timestamp = 1_000L;
        session.procesarDeteccion(steps[0], timestamp);
        for (int index = 0; index < steps.length; index++) {
            timestamp = accumulateStep(session, steps[index], timestamp, 400L);
            if (index + 1 < steps.length) {
                timestamp += 100L;
                session.procesarDeteccion(steps[index + 1], timestamp);
                timestamp += 100L;
                session.procesarDeteccion(steps[index + 1], timestamp);
            }
        }
        return timestamp;
    }

    private static long accumulateStep(WashSession session, WashStep step,
                                       long timestamp, long requiredMs) {
        int guard = 0;
        while (session.getTiempoPorPasoMs().getOrDefault(step, 0L) < requiredMs) {
            if (++guard > 20) throw new AssertionError("No se acumuló evidencia para " + step);
            timestamp += 100L;
            session.procesarDeteccion(step, timestamp);
        }
        return timestamp;
    }

    private static final class FastValidationStrategy implements com.handwash.strategy.ValidationRuleStrategy {
        @Override public boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs) {
            return tiempoAcumuladoMs >= getTiempoRequeridoPaso(paso);
        }
        @Override public long getTiempoRequeridoPaso(WashStep paso) { return 400L; }
        @Override public long getDuracionTotalMs() { return 40_000L; }
    }

    @Test
    void keepsAverageConfidenceForSessionSummary() {
        WashSession sesion = new WashSession("confidence", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L, 0.8f);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 2_000L, 0.6f);
        assertEquals(0.7, sesion.getConfianzaPromedio(), 0.0001);
    }

    @Test
    void repeatedWrongFramesDoNotFloodViolationHistory() {
        WashSession sesion = new WashSession("noise", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);
        for (int frame = 0; frame < 100; frame++) {
            sesion.procesarDeteccion(WashStep.PASO_3_INTERDIGITALES, 2_000L + frame * 20L);
        }

        assertEquals(1, sesion.getHistorialInfracciones().size());
        assertEquals(InfractionType.PASO_OMITIDO, sesion.getInfraccionActual().getTipo());
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals("ESPERANDO_INICIO", sesion.getEstadoSesion().name());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        assertTrue(sesion.getIntentosAnteriores().get(0).infracciones().size() >= 1);
    }
}
