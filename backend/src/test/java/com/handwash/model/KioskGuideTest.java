package com.handwash.model;

import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.ProtocolProfileStrategy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.handwash.model.KioskGuide.Codigo.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Java chooses one closed instruction per snapshot; the kiosk only translates it. */
class KioskGuideTest {
    private BalancedFrictionAttempt attempt() {
        return new BalancedFrictionAttempt("guide", new BalancedFrictionStrategy(
            new ProtocolProfileStrategy(ProtocolProfile.PREDETERMINADO)), 1500, 650);
    }

    private static void save(BalancedFrictionAttempt a) { a.persisted(a.pendingRecord()); }

    private static long dwell(BalancedFrictionAttempt a, WashStep step, long start, int seconds) {
        for (int i = 0; i <= seconds; i++) { a.observe(step, start + i * 1000L, 0); save(a); }
        return start + seconds * 1000L;
    }

    private static KioskGuide guide(BalancedFrictionAttempt a) { return a.snapshot().guia(); }

    @Test
    void startsWithPalmsThenCountsDownTheSevenSecondMovement() {
        var a = attempt();
        assertEquals(INICIA_CON_PALMAS, guide(a).codigo());

        dwell(a, WashStep.PASO_1_PALMAS, 1_000, 3);
        KioskGuide continuing = guide(a);
        assertEquals(CONTINUA_PASO, continuing.codigo());
        assertEquals("PASO_1_PALMAS", continuing.paso());
        assertEquals(4_000L, continuing.faltanteMs());
        assertEquals(KioskGuide.Tono.INFO, continuing.tono());
    }

    @Test
    void validatedMovementAnnouncesTheNextOneEvenWhileTheSwitchIsAmbiguous() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1_000, 7);
        assertEquals(CAMBIA_A_PASO, guide(a).codigo());
        assertEquals("PASO_2_DORSOS", guide(a).paso());

        a.pause("NO_RELIABLE_STEP");
        assertEquals(CAMBIA_A_PASO, guide(a).codigo(), "a background frame mid-switch keeps the switch cue");
    }

    @Test
    void earlySwitchAsksToReturnWithTheMissingTime() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1_000, 2);
        a.observe(WashStep.PASO_2_DORSOS, 3_200, 0);
        a.observe(WashStep.PASO_2_DORSOS, 3_400, 0);

        KioskGuide back = guide(a);
        assertEquals(VUELVE_A_PASO, back.codigo());
        assertEquals("PASO_1_PALMAS", back.paso());
        assertEquals(5_000L, back.faltanteMs());
        assertEquals(KioskGuide.Tono.CORRECCION, back.tono());
    }

    @Test
    void outOfOrderHandsAndNoiseHaveTheirOwnCorrections() {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1_000, 2);
        a.observe(WashStep.PASO_4_NUDILLOS, now + 200, 0);
        a.observe(WashStep.PASO_4_NUDILLOS, now + 400, 0);
        assertEquals(SIGUE_EL_ORDEN, guide(a).codigo());
        assertEquals("PASO_1_PALMAS", guide(a).paso());

        a.presence(1, now + 600, 500);
        assertEquals(MUESTRA_AMBAS_MANOS, guide(a).codigo());
        assertNull(guide(a).paso());

        a.pause("NO_RELIABLE_STEP");
        assertEquals(MANTEN_EL_MOVIMIENTO, guide(a).codigo());
    }

    @Test
    void technicalProblemsNeverBlameThePerson() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1_000, 2);
        a.pause("PRODUCER_UNREACHABLE");
        assertEquals(ESPERA_TECNICA, guide(a).codigo());
        assertEquals(KioskGuide.Tono.TECNICO, guide(a).tono());

        a.observe(WashStep.PASO_1_PALMAS, 4_000, 0);
        a.persistenceFailed();
        assertEquals(ESPERA_TECNICA, guide(a).codigo());
    }

    @Test
    void absenceAfterAStartedAttemptIsAnIncompleteWash() {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1_000, 3);
        for (long t = now + 500; t <= now + 11_000; t += 250) a.presence(0, t, 500);
        save(a);

        assertEquals(LAVADO_INCOMPLETO, guide(a).codigo());
        assertEquals(KioskGuide.Tono.ALERTA, guide(a).tono());
    }

    @Test
    void completeWashIsAnnouncedOnlyAfterItIsStored() {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1_000, 7);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0);
            a.observe(step, now += 200, 0);
            now = dwell(a, step, now + 1_000, 6);
        }
        assertEquals(LAVADO_COMPLETO, guide(a).codigo());
        assertEquals(KioskGuide.Tono.EXITO, guide(a).tono());
    }

    @Test
    void catalogIsClosed() {
        assertEquals(11, KioskGuide.Codigo.values().length,
            "adding a code requires updating the kiosk translations");
        assertEquals(5, KioskGuide.Tono.values().length);
        assertEquals("INICIA_CON_PALMAS", Arrays.stream(KioskGuide.Codigo.values()).findFirst().orElseThrow().name());
    }
}
