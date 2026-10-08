package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesionLavadoTest {

    @Test
    void fondoRestartsActiveAttemptAndKeepsOnlyErrorSummary() {
        SesionLavado sesion = new SesionLavado("s1", TipoProtocolo.CLINICO_QUIRURGICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);

        EstadoLavadoResponse response = sesion.procesarDeteccion(PasoLavado.FONDO, 2_000L);

        assertNull(response.getEstadoActual());
        assertEquals(Boolean.FALSE, response.getManoDetectada());
        assertEquals("ESPERANDO_INICIO", response.getEstadoSesion());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA, response.getInfraccion().getTipo());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            response.getUltimoErrorReinicio().getTipo());
        assertEquals(1, response.getIntentosReiniciados());
        assertEquals(1, sesion.getIntentosAnteriores().size());
    }

    @Test
    void inactivityExpiryArchivesAnActivePartialAttempt() {
        SesionLavado sesion = new SesionLavado("partial-expiry", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);

        sesion.expirar();

        assertEquals(EstadoSesion.EXPIRADA, sesion.getEstadoSesion());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        IntentoLavadoResumen attempt = sesion.getIntentosAnteriores().get(0);
        assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD", attempt.motivoReinicio());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            attempt.infracciones().get(0).getTipo());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            sesion.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
    }

    @Test
    void partialStepBoxMarksHandsVisibleOnlyDuringAnActiveObservedAttempt() {
        SesionLavado sesion = new SesionLavado("visible", TipoProtocolo.DOMESTICO);
        assertEquals(Boolean.FALSE, sesion.getEstadoActualResponse().getManoDetectada());

        EstadoLavadoResponse observed = sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);
        assertEquals(Boolean.TRUE, observed.getManoDetectada());

        EstadoLavadoResponse hidden = sesion.procesarDeteccion(PasoLavado.FONDO, 1_200L);
        assertEquals(Boolean.FALSE, hidden.getManoDetectada());
    }

    @Test
    void finalStepOnlyCompletesAfterStrategyMinimumAndNotOnWrongClass() {
        SesionLavado sesion = new SesionLavado("final", TipoProtocolo.DOMESTICO);
        long timestamp = 1_000L;
        PasoLavado[] pasos = {
            PasoLavado.PASO_1_PALMAS,
            PasoLavado.PASO_2_DORSOS,
            PasoLavado.PASO_3_INTERDIGITALES,
            PasoLavado.PASO_4_NUDILLOS,
            PasoLavado.PASO_5_PULGAR,
            PasoLavado.PASO_6_PUNTA_DE_DEDOS,
            PasoLavado.PASO_7_CIRCULARES
        };

        for (int index = 0; index < pasos.length; index++) {
            PasoLavado paso = pasos[index];
            sesion.procesarDeteccion(paso, timestamp);
            if (index > 0) {
                timestamp += 300L;
                sesion.procesarDeteccion(paso, timestamp);
            }
            timestamp += 2_700L;
        }
        assertEquals(EstadoSesion.EN_PROGRESO, sesion.getEstadoSesion());
        assertEquals(PasoLavado.PASO_7_CIRCULARES, sesion.getEstadoActual().getPasoActual());

        for (int i = 0; i < 8; i++) {
            timestamp += 1_000L;
            sesion.procesarDeteccion(PasoLavado.PASO_7_CIRCULARES, timestamp);
        }
        assertEquals(EstadoSesion.EN_PROGRESO, sesion.getEstadoSesion());
        timestamp += 1_000L;
        EstadoLavadoResponse response = sesion.procesarDeteccion(PasoLavado.PASO_7_CIRCULARES, timestamp);
        assertEquals(EstadoSesion.COMPLETADA, sesion.getEstadoSesion());
        assertEquals(7, response.getProgreso().getPasosCompletados());
        assertEquals(sesion.getTiempoPorPasoMs().values().stream().mapToLong(Long::longValue).sum(),
            response.getTiempoAcumuladoMs());
    }

    @Test
    void keepsAverageConfidenceForSessionSummary() {
        SesionLavado sesion = new SesionLavado("confidence", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L, 0.8f);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 2_000L, 0.6f);
        assertEquals(0.7, sesion.getConfianzaPromedio(), 0.0001);
    }

    @Test
    void repeatedWrongFramesDoNotFloodViolationHistory() {
        SesionLavado sesion = new SesionLavado("noise", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);
        for (int frame = 0; frame < 100; frame++) {
            sesion.procesarDeteccion(PasoLavado.PASO_3_INTERDIGITALES, 2_000L + frame * 20L);
        }

        assertEquals(1, sesion.getHistorialInfracciones().size());
        assertEquals(TipoInfraccion.PASO_OMITIDO, sesion.getInfraccionActual().getTipo());
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals("ESPERANDO_INICIO", sesion.getEstadoSesion().name());
        assertEquals(1, sesion.getIntentosAnteriores().size());
        assertTrue(sesion.getIntentosAnteriores().get(0).infracciones().size() >= 1);
    }
}
