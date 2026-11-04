package com.handwash.model;

import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.ProtocolProfileStrategy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact wall-clock start/end, monotonic duration and audit classification of an attempt. */
class WashReportTest {
    private static final long WALL_START = 1_792_600_000_000L;
    private final AtomicLong wall = new AtomicLong(WALL_START);

    private BalancedFrictionAttempt attempt(long slowAfterMs) {
        var profile = ProtocolProfile.PREDETERMINADO.siguienteVersion(
            ProtocolProfile.uniforme(7_000L), slowAfterMs, "admin", 1L);
        return new BalancedFrictionAttempt("report", new BalancedFrictionStrategy(
            new ProtocolProfileStrategy(profile)), 1500, 650, wall::get);
    }

    /** Monotonic and wall clocks advance together, as in production. */
    private long observe(BalancedFrictionAttempt a, WashStep step, long mono) {
        wall.set(WALL_START + mono);
        a.observe(step, mono, 0);
        return mono;
    }

    private long completeWash(BalancedFrictionAttempt a) {
        long now = 0;
        for (long t = 0; t <= 7_000; t += 1_000) now = observe(a, WashStep.PASO_1_PALMAS, t);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            observe(a, step, now += 200);
            observe(a, step, now += 200);
            for (int s = 0; s < 7; s++) now = observe(a, step, now + 1_000);
        }
        return now;
    }

    @Test
    void correctWashKeepsExactTimesAndIsNotSlowWithinSixtySeconds() {
        var a = attempt(60_000);
        long end = completeWash(a);
        var record = a.pendingRecord();

        assertEquals(WashOutcome.CORRECTO, record.outcome());
        assertEquals(WALL_START, record.startedAtEpochMs());
        assertEquals(WALL_START + end, record.finishedAtEpochMs());
        assertEquals(end, record.durationMs());
        assertFalse(record.slow());
        assertNull(record.evidenceStatus());
    }

    @Test
    void washLongerThanTheProfileThresholdIsOnlyAnnotatedAsSlow() {
        var a = attempt(42_000);
        completeWash(a);
        var record = a.pendingRecord();

        assertEquals(WashOutcome.CORRECTO, record.outcome());
        assertTrue(record.slow(), "duration " + record.durationMs() + " ms exceeds 42 s");
    }

    @Test
    void leavingAnUnfinishedAttemptIsReportedAtTheMomentTheHandsLeft() {
        var a = attempt(60_000);
        long now = 0;
        for (long t = 0; t <= 3_000; t += 1_000) now = observe(a, WashStep.PASO_1_PALMAS, t);
        long left = now + 500;
        for (long t = left; t <= left + 10_250; t += 250) {
            wall.set(WALL_START + t);
            a.presence(0, t, 500);
        }
        var record = a.pendingRecord();

        assertEquals(WashOutcome.REPORTADO, record.outcome());
        assertEquals(WALL_START + left, record.finishedAtEpochMs());
        assertEquals(left, record.durationMs());
        assertFalse(record.slow());
    }

    @Test
    void technicalEndsAndContaminationAreNeverAbandonment() {
        var closed = attempt(60_000);
        observe(closed, WashStep.PASO_1_PALMAS, 0);
        closed.interrupt("SESSION_CLOSED");
        assertEquals(WashOutcome.INTERRUMPIDO, closed.pendingRecord().outcome());

        var contaminated = attempt(60_000);
        observe(contaminated, WashStep.PASO_1_PALMAS, 0);
        contaminated.confirmedContamination();
        assertEquals(WashOutcome.REINICIADO, contaminated.pendingRecord().outcome());

        var running = attempt(60_000);
        observe(running, WashStep.PASO_1_PALMAS, 0);
        assertNull(running.pendingRecord().outcome(), "an attempt in progress has no outcome yet");
        assertEquals(WALL_START, running.pendingRecord().startedAtEpochMs());
    }
}
