package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandwashingSessionTest {

    @Test
    void fondoRestartsActiveAttemptAndKeepsOnlyErrorSummary() {
        HandwashingSession sesion = new HandwashingSession("s1", ProtocolType.CLINICO_QUIRURGICO);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L);

        HandwashingStatusResponse response = sesion.procesarDeteccion(HandwashingStep.FONDO, 2_000L);

        assertNull(response.getEstadoActual());
        assertEquals(Boolean.FALSE, response.getManoDetectada());
        assertEquals("ESPERANDO_INICIO", response.getEstadoSesion());
        assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA, response.getInfraccion().getTipo());
        assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
            response.getUltimoErrorReinicio().getTipo());
        assertEquals(1, response.getIntentosReiniciados());
        assertEquals(1, sesion.getIntentosAnteriores().size());
    }

    @Test
    void inactivityExpiryArchivesAnActivePartialAttempt() {
        HandwashingSession sesion = new HandwashingSession("partial-expiry", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L);

        sesion.expirar();

        assertEquals(HandwashingSessionState.EXPIRADA, sesion.getEstadoSesion());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        HandwashingAttemptSummary attempt = sesion.getIntentosAnteriores().get(0);
        assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD", attempt.motivoReinicio());
        assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
            attempt.infracciones().get(0).getTipo());
        assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
            sesion.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
    }

    @Test
    void partialStepBoxMarksHandsVisibleOnlyDuringAnActiveObservedAttempt() {
        HandwashingSession sesion = new HandwashingSession("visible", ProtocolType.DOMESTICO);
        assertEquals(Boolean.FALSE, sesion.getEstadoActualResponse().getManoDetectada());

        HandwashingStatusResponse observed = sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L);
        assertEquals(Boolean.TRUE, observed.getManoDetectada());

        HandwashingStatusResponse hidden = sesion.procesarDeteccion(HandwashingStep.FONDO, 1_200L);
        assertEquals(Boolean.FALSE, hidden.getManoDetectada());
    }

    @Test
    void finalStepOnlyCompletesAfterStrategyMinimumAndNotOnWrongClass() {
        HandwashingSession sesion = new HandwashingSession("final", ProtocolType.DOMESTICO);
        long timestamp = 1_000L;
        HandwashingStep[] pasos = {
            HandwashingStep.PASO_1_PALMAS,
            HandwashingStep.PASO_2_DORSOS,
            HandwashingStep.PASO_3_INTERDIGITALES,
            HandwashingStep.PASO_4_NUDILLOS,
            HandwashingStep.PASO_5_PULGAR,
            HandwashingStep.PASO_6_PUNTA_DE_DEDOS,
            HandwashingStep.PASO_7_CIRCULARES
        };

        for (int index = 0; index < pasos.length; index++) {
            HandwashingStep paso = pasos[index];
            sesion.procesarDeteccion(paso, timestamp);
            if (index > 0) {
                timestamp += 300L;
                sesion.procesarDeteccion(paso, timestamp);
            }
            timestamp += 2_700L;
        }
        assertEquals(HandwashingSessionState.EN_PROGRESO, sesion.getEstadoSesion());
        assertEquals(HandwashingStep.PASO_7_CIRCULARES, sesion.getEstadoActual().getPasoActual());

        for (int i = 0; i < 8; i++) {
            timestamp += 1_000L;
            sesion.procesarDeteccion(HandwashingStep.PASO_7_CIRCULARES, timestamp);
        }
        assertEquals(HandwashingSessionState.EN_PROGRESO, sesion.getEstadoSesion());
        timestamp += 1_000L;
        HandwashingStatusResponse response = sesion.procesarDeteccion(HandwashingStep.PASO_7_CIRCULARES, timestamp);
        assertEquals(HandwashingSessionState.COMPLETADA, sesion.getEstadoSesion());
        assertEquals(7, response.getProgreso().getPasosCompletados());
        assertEquals(sesion.getTiempoPorPasoMs().values().stream().mapToLong(Long::longValue).sum(),
            response.getTiempoAcumuladoMs());
    }

    @Test
    void keepsAverageConfidenceForSessionSummary() {
        HandwashingSession sesion = new HandwashingSession("confidence", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L, 0.8f);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 2_000L, 0.6f);
        assertEquals(0.7, sesion.getConfianzaPromedio(), 0.0001);
    }

    @Test
    void repeatedWrongFramesDoNotFloodViolationHistory() {
        HandwashingSession sesion = new HandwashingSession("noise", ProtocolType.DOMESTICO);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L);
        for (int frame = 0; frame < 100; frame++) {
            sesion.procesarDeteccion(HandwashingStep.PASO_3_INTERDIGITALES, 2_000L + frame * 20L);
        }

        assertEquals(1, sesion.getHistorialInfracciones().size());
        assertEquals(ViolationType.PASO_OMITIDO, sesion.getInfraccionActual().getTipo());
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals("ESPERANDO_INICIO", sesion.getEstadoSesion().name());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        assertTrue(sesion.getIntentosAnteriores().get(0).infracciones().size() >= 1);
    }
}
