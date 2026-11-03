package com.handwash.intention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandPresenceWarmupTest {
    private static final long MS = 1_000_000L;

    @Test
    void requiresThreeSecondsOfDistinctFreshBilateralObservations() {
        HandPresenceWarmup warmup = new HandPresenceWarmup(3_000, 500);
        assertFalse(warmup.observe(1, 2, 0, false));
        for (int frame = 2; frame <= 6; frame++) {
            assertFalse(warmup.observe(frame, 2, (frame - 1L) * 500 * MS, false));
        }
        assertTrue(warmup.observe(7, 2, 3_000 * MS, false));
        assertTrue(warmup.permitsStep(3_000 * MS, false));
    }

    @Test
    void missingHandOrLongGapRestartsTheInitialDwell() {
        HandPresenceWarmup warmup = new HandPresenceWarmup(3_000, 500);
        warmup.observe(1, 2, 0, false);
        warmup.observe(2, 2, 400 * MS, false);
        assertFalse(warmup.observe(3, 1, 500 * MS, false));
        assertFalse(warmup.observe(4, 2, 600 * MS, false));
        assertFalse(warmup.observe(5, 2, 1_101 * MS, false));
        assertFalse(warmup.permitsStep(1_101 * MS, false));
    }

    @Test
    void duplicateFramesCannotAdvanceDwellAndResetTheProducerEpoch() {
        HandPresenceWarmup warmup = new HandPresenceWarmup(3_000, 500);
        warmup.observe(8, 2, 0, false);
        assertFalse(warmup.observe(8, 2, 3_000 * MS, false));
        assertFalse(warmup.permitsStep(3_000 * MS, false));

        warmup.reset();
        assertFalse(warmup.isReady());
        assertFalse(warmup.observe(0, 2, 4_000 * MS, false));
        assertTrue(warmup.lastFrameSequence() == 0L);
    }

    @Test
    void readinessLatchesAfterStartButEachStepStillNeedsFreshBilateralPresence() {
        HandPresenceWarmup warmup = new HandPresenceWarmup(1_000, 500);
        warmup.observe(1, 2, 0, false);
        assertFalse(warmup.observe(2, 2, 400 * MS, false));
        assertFalse(warmup.observe(3, 2, 800 * MS, false));
        assertTrue(warmup.observe(4, 2, 1_000 * MS, false));
        assertFalse(warmup.observe(5, 1, 1_100 * MS, true));
        assertTrue(warmup.isReady());
        assertFalse(warmup.permitsStep(1_100 * MS, true));
        assertTrue(warmup.observe(6, 2, 1_200 * MS, true));
        assertTrue(warmup.permitsStep(1_200 * MS, true));
        assertFalse(warmup.permitsStep(1_701 * MS, true));
        assertTrue(warmup.observe(7, 2, 1_800 * MS, true),
            "a long pause after wash start requires fresh hands but not another initial dwell");
    }

    @Test
    void validatesConfigurationBounds() {
        assertThrows(IllegalArgumentException.class, () -> new HandPresenceWarmup(-1, 500));
        assertThrows(IllegalArgumentException.class, () -> new HandPresenceWarmup(3_000, 99));
        assertThrows(IllegalArgumentException.class, () -> new HandPresenceWarmup(10_001, 500));
    }
}
