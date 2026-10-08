package com.handwash.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesionLavadoTimingTest {
    @Test
    void ignoresLongGapsBetweenDetections() {
        SesionLavado sesion = new SesionLavado("timing", TipoProtocolo.DOMESTICO);

        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 2_000L);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 10_000L);

        assertEquals(1_000L, sesion.getTiempoPorPasoMs().get(PasoLavado.PASO_1_PALMAS));
    }

    @Test
    void firstLongGapDoesNotCreditUnobservedTime() {
        SesionLavado sesion = new SesionLavado("first-gap", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);

        EstadoLavadoResponse state = sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 10_000L);

        assertEquals(0L, state.getTiempoAcumuladoMs());
        assertEquals(EstadoSesion.EN_PROGRESO, sesion.getEstadoSesion());
    }

    @Test
    void configurableGapSupportsSlowerCamerasWithoutChangingProtocolRules() {
        SesionLavado sesion = new SesionLavado("slow-camera", TipoProtocolo.DOMESTICO,
            new com.handwash.strategy.DomesticoStrategy(), 2_500L);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);

        EstadoLavadoResponse state = sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 3_000L);

        assertEquals(2_000L, state.getTiempoAcumuladoMs());

        sesion.procesarDeteccion(PasoLavado.PASO_2_DORSOS, 4_500L);

        assertEquals(3_500L, sesion.getTiempoPorPasoMs().get(PasoLavado.PASO_1_PALMAS));
    }

    @Test
    void rejectsOutOfOrderFramesWithoutChangingStateOrDuration() {
        SesionLavado sesion = new SesionLavado("late-frame", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L, 0.8f);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 2_000L, 0.6f);

        sesion.procesarDeteccion(PasoLavado.PASO_2_DORSOS, 1_500L, 0.0f);

        assertEquals(PasoLavado.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual());
        assertEquals(1_000L, sesion.getTiempoPorPasoMs().get(PasoLavado.PASO_1_PALMAS));
        assertEquals(0.7, sesion.getConfianzaPromedio(), 0.0001);
        assertTrue(sesion.getHistorialInfracciones().isEmpty());
    }

    @Test
    void inactivityExpiryUsesMonotonicElapsedTimeInsteadOfWallClock() {
        SesionLavado sesion = new SesionLavado("monotonic-idle", TipoProtocolo.DOMESTICO);
        long lastActivityNanos = (long) ReflectionTestUtils.getField(
            sesion, "ultimaActividadMonotonicNanos");
        long lastActivityEpochMs = sesion.getUltimaActividadTimestamp();

        // A backwards wall-clock correction cannot extend the monotonic deadline.
        long wallClockBehind = lastActivityEpochMs - TimeUnit.DAYS.toMillis(1);
        assertFalse(sesion.estaInactivaDesde(wallClockBehind,
            lastActivityNanos + TimeUnit.SECONDS.toNanos(59), 60_000L));
        assertTrue(sesion.estaInactivaDesde(wallClockBehind,
            lastActivityNanos + TimeUnit.SECONDS.toNanos(60), 60_000L));

        // Wall time also makes a long Mac sleep count even if monotonic time advanced less.
        assertTrue(sesion.estaInactivaDesde(lastActivityEpochMs + TimeUnit.MINUTES.toMillis(2),
            lastActivityNanos + TimeUnit.SECONDS.toNanos(1), 60_000L));
    }

    @Test
    void failedAttemptHistoryDurationUsesMonotonicTime() {
        SesionLavado sesion = new SesionLavado("failed-attempt-duration", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);
        ReflectionTestUtils.setField(sesion, "inicioIntentoMonotonicNanos",
            System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        sesion.reiniciarIntento("test");

        long durationMs = sesion.getIntentosAnteriores().get(0).duracionMs();
        assertTrue(durationMs >= 1_500L, "el resumen debe usar el inicio monotónico del intento");
        assertTrue(durationMs < 10_000L, "la duración del intento debe permanecer acotada");
    }

    @Test
    void sessionDurationDoesNotCollapseWhenWallClockMovesBackBeforeSessionStart() throws InterruptedException {
        SesionLavado session = new SesionLavado("monotonic-session-duration", TipoProtocolo.DOMESTICO);
        Thread.sleep(5L);
        session.expirar();
        ReflectionTestUtils.setField(session, "finTimestamp", session.getInicioTimestamp() - 1_000L);

        assertTrue(session.getDuracionMs() >= 1L);
    }

    @Test
    void repeatedResetDoesNotArchiveTheSamePartialAttemptTwice() {
        SesionLavado sesion = new SesionLavado("repeated-reset", TipoProtocolo.DOMESTICO);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);

        sesion.reiniciarIntento("first-reset");
        sesion.reiniciarIntento("duplicate-reset");

        assertEquals(1, sesion.getIntentosAnteriores().size());
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals("first-reset", sesion.getIntentosAnteriores().get(0).motivoReinicio());
    }
}
