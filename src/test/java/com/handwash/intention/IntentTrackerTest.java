package com.handwash.intention;

import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentTrackerTest {
    @Test
    void recentEvidenceDoesNotAssumeMonotonicClockHasPositiveOrigin() {
        long updatedAt = -TimeUnit.SECONDS.toNanos(5);

        assertTrue(IntentTracker.actualizacionReciente(updatedAt,
            updatedAt + TimeUnit.SECONDS.toNanos(1)));
        assertTrue(IntentTracker.actualizacionReciente(updatedAt,
            updatedAt + TimeUnit.MILLISECONDS.toNanos(1_500)));
        assertFalse(IntentTracker.actualizacionReciente(updatedAt,
            updatedAt + TimeUnit.MILLISECONDS.toNanos(1_501)));
        assertFalse(IntentTracker.actualizacionReciente(updatedAt,
            updatedAt - 1L));
    }

    @Test
    void outOfOrderFreshFrameCannotRewindActiveWashClock() {
        IntentTracker tracker = new IntentTracker();
        HandwashingIntentChain chain = new HandwashingIntentChain(650, 650, 3, 1500, 0.75);

        assertTrue(tracker.evaluar(event(1, 5_000L), true, chain));
        assertFalse(tracker.evaluar(event(2, 4_500L), true, chain));
        assertEquals("OBSERVACION_FUERA_DE_ORDEN", tracker.ultimoCodigoRechazo());
        assertFalse(tracker.debeReiniciar(6_000L, chain),
            "a reordered request must not cause an early pause reset");
    }

    @Test
    void discardedNewerFrameStillPreventsAnOlderFrameFromRewindingTheClock() {
        IntentTracker tracker = new IntentTracker();
        HandwashingIntentChain chain = new HandwashingIntentChain(650, 650, 3, 1500, 0.75);

        assertTrue(tracker.evaluar(event(1, 5_000L), true, chain));
        tracker.descartar(event(2, 5_500L), true, "Confianza bajo el umbral");

        assertFalse(tracker.evaluar(event(3, 5_200L), true, chain));
        assertEquals("OBSERVACION_FUERA_DE_ORDEN", tracker.ultimoCodigoRechazo());
        assertFalse(tracker.debeReiniciar(6_000L, chain));
    }

    @Test
    void appliesStartAndStepMovementThresholdsIndependently() {
        HandwashingIntentChain chain = new HandwashingIntentChain(
            650, 650, 3, 1500, 0.75, 0.15, 0.25);

        assertEquals("MOVIMIENTO_INICIAL_NO_DETECTADO",
            chain.evaluar(event(1, 5_000L, "Paso1_Palmas", 0.14), false));
        assertNull(chain.evaluar(event(2, 5_100L, "Paso1_Palmas", 0.16), false));
        assertEquals("MOVIMIENTO_ACTIVO_NO_DETECTADO",
            chain.evaluar(event(3, 5_200L, "Paso2_Dorsos", 0.24), true));
        assertNull(chain.evaluar(event(4, 5_300L, "Paso2_Dorsos", 0.26), true));
        assertThrows(IllegalArgumentException.class, () -> new HandwashingIntentChain(
            650, 650, 3, 1500, 0.75, 0.0, 0.25));
    }

    private static DetectionEvent event(long sequence, long serverTimeMs) {
        return event(sequence, serverTimeMs, "Paso1_Palmas", 0.2);
    }

    private static DetectionEvent event(long sequence, long serverTimeMs,
                                         String action, double movement) {
        DetectionEvent event = new DetectionEvent(
            "out-of-order", action, 0.9f, Instant.now().toString());
        event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, movement, true, 0L));
        event.setServerReceivedAtMonotonicMs(serverTimeMs);
        return event;
    }
}
