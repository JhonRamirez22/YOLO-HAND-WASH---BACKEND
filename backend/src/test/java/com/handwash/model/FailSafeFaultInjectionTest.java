package com.handwash.model;

import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.DomesticStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-01 / R-02: whatever fails around the attempt (storage, producer, clock), the kiosk never gets
 * a "correct" result Java did not store, and a technical fault is never blamed on the person.
 */
class FailSafeFaultInjectionTest {
    private static BalancedFrictionAttempt attempt() {
        return new BalancedFrictionAttempt("fault", new BalancedFrictionStrategy(new DomesticStrategy()), 1500, 650);
    }

    private static void save(BalancedFrictionAttempt a) {
        var record = a.pendingRecord();
        if (record != null) a.persisted(record);
    }

    private static long dwell(BalancedFrictionAttempt a, WashStep step, long start, int seconds, boolean ack) {
        for (int i = 0; i <= seconds; i++) {
            a.observe(step, start + i * 1000L, 0);
            if (ack) save(a);
        }
        return start + seconds * 1000L;
    }

    /** Six validated movements; {@code ack} says whether storage acknowledges each change. */
    private static BalancedFrictionAttempt completeWash(boolean ack) {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1000, 6, ack);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0);
            a.observe(step, now += 200, 0);
            now = dwell(a, step, now + 1000, i < 4 ? 5 : 3, ack);
        }
        return a;
    }

    @Test
    void aWashStorageHasNotAcknowledgedIsNeverPresentedAsComplete() {
        var a = completeWash(false);

        var shown = a.snapshot();
        assertFalse(shown.secuenciaValidada());
        assertTrue(shown.persistenciaPendiente());
        assertEquals("PENDIENTE_PERSISTENCIA", shown.estadoIntento());
        assertEquals(KioskGuide.Codigo.ESPERA_TECNICA, shown.guia().codigo());
        assertNull(shown.ultimoResultado());

        a.persistenceFailed();
        a.persisted(null);
        assertEquals(KioskGuide.Codigo.ESPERA_TECNICA, a.snapshot().guia().codigo());
        assertNull(a.snapshot().ultimoResultado());

        save(a);
        var stored = a.snapshot();
        assertTrue(stored.secuenciaValidada());
        assertEquals(KioskGuide.Codigo.LAVADO_COMPLETO, stored.guia().codigo());
        assertEquals("FRICTION_SEQUENCE_COMPLETE", stored.ultimoResultado().resultado());
    }

    @Test
    void aStorageOutageNeitherCreditsTimeNorBackfillsTheGapAfterRecovery() {
        var a = attempt();
        a.observe(WashStep.PASO_1_PALMAS, 1000, 0);
        a.observe(WashStep.PASO_1_PALMAS, 2000, 0);
        long creditedBeforeOutage = a.currentMs();
        a.persistenceFailed();

        for (long t = 3000; t <= 9000; t += 1000) a.observe(WashStep.PASO_1_PALMAS, t, 0);
        assertEquals(creditedBeforeOutage, a.currentMs(), "no credit while storage is down");
        assertEquals("PENDIENTE_PERSISTENCIA", a.snapshot().estadoIntento());

        save(a);
        a.observe(WashStep.PASO_1_PALMAS, 10_000, 0);
        assertEquals(creditedBeforeOutage, a.currentMs(), "the outage gap is not credited retroactively");
        a.observe(WashStep.PASO_1_PALMAS, 11_000, 0);
        assertEquals(creditedBeforeOutage + 1000, a.currentMs());
    }

    @Test
    void aSilentProducerPausesTheWashAndTheSilenceIsNeverCredited() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 3, true);
        long credited = a.currentMs();

        a.checkFreshness(9000, 1000);
        assertEquals("PAUSADO", a.snapshot().estadoIntento());
        assertEquals(KioskGuide.Codigo.ESPERA_TECNICA, a.snapshot().guia().codigo());

        a.observe(WashStep.PASO_1_PALMAS, 9500, 0);
        assertEquals(credited, a.currentMs(), "first frame after the silence only restarts the clock");
        assertFalse(a.snapshot().secuenciaValidada());
    }

    @Test
    void replayedDuplicatedOrBackwardsTimestampsCannotManufactureProgress() {
        var a = attempt();
        a.observe(WashStep.PASO_1_PALMAS, 5000, 0);
        for (int i = 0; i < 500; i++) a.observe(WashStep.PASO_1_PALMAS, 5000, 0);
        for (int i = 0; i < 500; i++) a.observe(WashStep.PASO_1_PALMAS, 4000 - i, 0);
        a.observe(WashStep.PASO_1_PALMAS, -1, 0);
        assertEquals(0, a.currentMs());
        assertEquals(0, a.validated());
    }

    @Test
    void technicalEndsAreNeverReportedAsTheAbandonmentOfAPerson() {
        var closed = attempt();
        dwell(closed, WashStep.PASO_1_PALMAS, 1000, 2, true);
        closed.interrupt("SESSION_CLOSED");
        assertEquals(WashOutcome.INTERRUMPIDO, WashOutcome.desde("INTERRUPTED", "SESSION_CLOSED"));
        assertEquals(KioskGuide.Codigo.ESPERA_TECNICA, closed.snapshot().guia().codigo());

        // Presence pulses that arrive with gaps prove nothing about absence.
        var gappy = attempt();
        dwell(gappy, WashStep.PASO_1_PALMAS, 1000, 2, true);
        for (long t = 5_000; t <= 120_000; t += 10_000) gappy.presence(0, t, 2_000);
        assertFalse(isTerminal(gappy), "gapped absence pulses must not end the attempt");
        assertNull(gappy.snapshot().ultimoResultado());

        // A replaced producer restarts the absence clock.
        var replaced = attempt();
        dwell(replaced, WashStep.PASO_1_PALMAS, 1000, 2, true);
        replaced.presence(0, 5_000, 2_000);
        replaced.presence(0, 9_000, 2_000);
        replaced.producerReplaced();
        replaced.presence(0, 14_500, 2_000);
        assertFalse(isTerminal(replaced));
    }

    @Test
    void onlyContinuousConfirmedAbsenceOfAStartedAttemptIsAReport() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 2, true);
        for (long t = 4_000; t <= 14_500; t += 500) a.presence(0, t, 1_000);
        save(a);

        assertNotNull(a.snapshot().ultimoResultado());
        assertEquals("INTERRUPTED", a.snapshot().ultimoResultado().resultado());
        assertEquals("HANDS_ABSENT", a.snapshot().ultimoResultado().motivo());
        assertEquals(WashOutcome.REPORTADO, WashOutcome.desde("INTERRUPTED", "HANDS_ABSENT"));
        assertEquals(WashOutcome.INTERRUMPIDO, WashOutcome.desde("INTERRUPTED", null));
        assertEquals(WashOutcome.INTERRUMPIDO, WashOutcome.desde("INTERRUPTED", "PRODUCER_UNREACHABLE"));
        assertEquals(WashOutcome.CORRECTO, WashOutcome.desde("FRICTION_SEQUENCE_COMPLETE", null));
    }

    @Test
    void anAttemptThatEndedTechnicallyCanNeverBecomeCorrectAfterwards() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 3, true);
        a.interrupt("SESSION_CLOSED");
        save(a);

        long now = 20_000;
        for (var step : BalancedFrictionStrategy.STEPS) now = dwell(a, step, now + 1000, 8, true);

        assertEquals(0, a.validated(), "no further credit after the end");
        assertFalse(a.snapshot().secuenciaValidada());
        assertEquals("INTERRUPTED", a.snapshot().ultimoResultado().resultado());
    }

    private static boolean isTerminal(BalancedFrictionAttempt a) {
        var pending = a.pendingRecord();
        return a.snapshot().ultimoResultado() != null || (pending != null && pending.terminal());
    }
}
