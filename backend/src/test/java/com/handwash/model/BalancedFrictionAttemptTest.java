package com.handwash.model;

import com.handwash.strategy.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.handwash.model.FrictionEvaluation.StepStatus.*;

class BalancedFrictionAttemptTest {
    private BalancedFrictionAttempt attempt() {
        return new BalancedFrictionAttempt("test", new BalancedFrictionStrategy(new DomesticStrategy()), 1500, 650);
    }
    private void save(BalancedFrictionAttempt a) { a.persisted(a.pendingRecord()); }
    private long dwell(BalancedFrictionAttempt a, WashStep step, long start, int seconds) {
        for (int i = 0; i <= seconds; i++) { a.observe(step, start + i * 1000L, 0); save(a); }
        return start + seconds * 1000L;
    }
    @Test void sixValidatedWithDistinctProcedureReferenceAndDurableCompletion() {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0);
            a.observe(step, now += 200, 0);
            now = dwell(a, step, now + 1000, i < 4 ? 5 : 3);
        }
        assertEquals(6, a.validated());
        assertTrue(a.snapshot().secuenciaValidada());
        assertEquals(32000, a.snapshot().duracionMinimaFriccionMs());
        assertEquals(40000, a.snapshot().objetivoProcedimientoMs());
        assertTrue(a.snapshot().pasos().stream().allMatch(p -> p.estado() == VALIDADO));
        assertEquals("FRICTION_SEQUENCE_COMPLETE", a.snapshot().ultimoResultado().resultado());
    }
    @Test void shortStepBlocksThenResumesWithoutLosingValidatedProgress() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 2);
        a.observe(WashStep.PASO_2_DORSOS, 3200, 0);
        assertEquals(EN_CURSO, a.snapshot().pasos().get(0).estado());
        a.observe(WashStep.PASO_2_DORSOS, 3400, 0);
        assertEquals(REQUIERE_REPETICION, a.snapshot().pasos().get(0).estado());
        assertEquals(WashStep.PASO_1_PALMAS, a.expected());
        dwell(a, WashStep.PASO_1_PALMAS, 4000, 4);
        assertEquals(VALIDADO, a.snapshot().pasos().get(0).estado());
        assertEquals(6000, a.currentMs());
        a.observe(WashStep.PASO_4_NUDILLOS, 8200, 0);
        a.observe(WashStep.PASO_4_NUDILLOS, 8400, 0);
        assertEquals(1, a.validated());
        assertEquals("STEP_OUT_OF_SEQUENCE", a.snapshot().motivoRecuperacion());
    }
    @Test void pauseAndNetworkGapDoNotCreditOrProveAbsence() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 2);
        a.pause("NO_RELIABLE_STEP");
        a.observe(WashStep.PASO_1_PALMAS, 20000, 0);
        assertEquals(2000, a.currentMs());
        a.presence(0, 21000, 500);
        a.presence(0, 41000, 500);
        assertNotEquals("INTERRUMPIDO", a.snapshot().estadoIntento());
        assertEquals(1, a.number());
    }
    @Test void tenSecondsContinuousAbsenceArchivesOnceThenNewAttempt() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (long t = 8000; t < 18000; t += 250) a.presence(0, t, 500);
        assertEquals(1, a.validated());
        a.presence(0, 18000, 500);
        assertEquals("PENDIENTE_PERSISTENCIA", a.snapshot().estadoIntento());
        save(a);
        assertEquals("HANDS_ABSENT", a.snapshot().ultimoResultado().motivo());
        assertTrue(a.presence(0, 18250, 500));
        assertEquals(2, a.number());
        assertEquals(0, a.validated());
        for (long t = 18500; t < 40000; t += 250) a.presence(0, t, 500);
        assertEquals(2, a.number());
    }
    @Test void missingHandRecoveryAndDuplicateDoNotChangeCredit() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 2);
        a.presence(1, 3500, 500);
        a.observe(WashStep.PASO_1_PALMAS, 3000, 0);
        a.observe(WashStep.PASO_1_PALMAS, 2900, 0);
        a.observe(WashStep.PASO_1_PALMAS, 4000, 0);
        assertEquals(2000, a.currentMs());
        assertEquals(1, a.number());
    }
    @Test void persistenceFailureBlocksCreditAndRequiresExactRetry() {
        var a = attempt();
        a.observe(WashStep.PASO_1_PALMAS, 1000, 0);
        var record = a.pendingRecord();
        a.persistenceFailed();
        a.observe(WashStep.PASO_1_PALMAS, 2000, 0);
        assertEquals(0, a.currentMs());
        assertEquals("PENDIENTE_PERSISTENCIA", a.snapshot().estadoIntento());
        a.persisted(record);
        a.observe(WashStep.PASO_1_PALMAS, 3000, 0);
        assertEquals(0, a.currentMs());
        assertFalse(a.snapshot().persistenciaPendiente());
    }
    @Test void factoryDefaultsToLegacyAndClinicalMinimaAre48Seconds() {
        assertEquals("LEGACY_V1", new ValidationRuleStrategyFactory().crear(ProtocolType.DOMESTICO).getVersionPolitica());
        var strategy = new BalancedFrictionStrategy(new SurgicalClinicalStrategy());
        assertEquals(48000, strategy.getDuracionMinimaFriccionMs());
        assertFalse(strategy.validarTiempoPaso(WashStep.PASO_7_CIRCULARES, 100000));
    }
    @Test void completionRequiresStorageThenThreeSecondsOfConfirmedAbsence() {
        var a = attempt();
        long now = dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0);
            a.observe(step, now += 200, 0);
            for (int n = 0; n < (i < 4 ? 6 : 4); n++) a.observe(step, now += 1000, 0);
            if (i != 5) save(a);
        }
        assertFalse(a.snapshot().secuenciaValidada());
        assertEquals("PENDIENTE_PERSISTENCIA", a.snapshot().estadoIntento());
        save(a);
        assertTrue(a.snapshot().secuenciaValidada());
        long start = now + 250;
        for (long t = start; t < start + 3000; t += 250) assertFalse(a.presence(0,t,500));
        assertTrue(a.presence(0,start+3000,500));
        assertEquals(2,a.number());
        assertEquals("FRICTION_SEQUENCE_COMPLETE",a.snapshot().ultimoResultado().resultado());
        assertEquals(1,a.snapshot().ultimoResultado().numeroIntento());
    }
    @Test void returningAfterTenSecondTimeoutStartsNewAttemptAndNeverResumesArchivedOne() {
        var a = attempt();
        dwell(a,WashStep.PASO_1_PALMAS,1000,2);
        for (long t = 4000; t <= 14000; t += 250) a.presence(0,t,500);
        save(a);
        assertTrue(a.presence(2,14250,500));
        assertEquals(2,a.number());
        assertEquals(0,a.currentMs());
    }
    @Test void onlyExplicitContaminationInvalidatesValidatedSteps() {
        var a=attempt();
        dwell(a,WashStep.PASO_1_PALMAS,1000,6);
        a.confirmedContamination();
        assertEquals(1,a.validated());
        save(a);
        assertEquals("RESET_RISK",a.snapshot().ultimoResultado().resultado());
        assertTrue(a.presence(2,8000,500));
        assertEquals(0,a.validated());
    }
    @Test void silentProducerPausesAndDoesNotCountAsAbsence() {
        var a=attempt();
        dwell(a,WashStep.PASO_1_PALMAS,1000,2);
        a.presence(0,3250,500);
        a.checkFreshness(15000,500);
        assertEquals("PRODUCER_UNREACHABLE",a.snapshot().motivoRecuperacion());
        assertEquals(1,a.number());
        a.presence(0,15250,500);
        assertNotEquals("INTERRUMPIDO",a.snapshot().estadoIntento());
        assertEquals(2000,a.currentMs());
    }
    @Test void candidateTelemetryAdvancesRevisionWithoutWritingOrCreditingAnAttempt() {
        var session=new WashSession("candidate",ProtocolType.DOMESTICO,new BalancedFrictionStrategy(new DomesticStrategy()));
        var event=new DetectionEvent("candidate","PASO_1_PALMAS",0.95f,java.time.Instant.now().toString());
        event.setEvidenciaMovimiento(new MovementEvidence(1L,2,0.2,true,0L));
        event.setServerReceivedAtMonotonicMs(1000);
        var chain=new com.handwash.intention.WashIntentionChain(650,650,3,1500,0.75);
        assertFalse(session.evaluarIntencion(event,chain));
        long revision=session.getEstadoActualResponse().getEvaluacionFriccion().revisionEstado();
        assertTrue(revision>0);
        assertNull(session.pendingFrictionRecord());
        assertEquals(0,session.getTiempoTotalActivoMs());
        assertFalse(session.evaluarIntencion(event,chain));
        assertEquals(revision,session.getEstadoActualResponse().getEvaluacionFriccion().revisionEstado());
    }

    // A wrong step that is HELD (not a flicker) sends the person back to the first step, with a notice first.
    @Test void aWrongStepHeldForSixSecondsRestartsTheWashFromTheFirstStepAfterTheNoticeWasShown() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (long t = 8000; t <= 14000; t += 250) { a.observe(WashStep.PASO_4_NUDILLOS, t, 0); save(a); }

        assertEquals("INTERRUMPIDO", a.snapshot().estadoIntento());
        assertEquals(KioskGuide.Codigo.REINICIA_LAVADO, a.snapshot().guia().codigo());
        assertEquals("RESET_RISK", a.snapshot().ultimoResultado().resultado());
        assertEquals("SEQUENCE_RESTART", a.snapshot().ultimoResultado().motivo());
        assertEquals(1, a.validated(), "the notice still shows what was achieved");

        assertFalse(a.presence(2, 15000, 500), "the notice stays visible for a few seconds");
        assertEquals(1, a.number());
        assertTrue(a.presence(2, 18250, 500));
        assertEquals(2, a.number());
        assertEquals(0, a.validated());
        assertEquals(WashStep.PASO_1_PALMAS, a.expected());
    }

    @Test void aWrongStepHeldForLessThanSixSecondsOnlyPausesAndKeepsTheProgress() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (long t = 8000; t <= 12500; t += 250) { a.observe(WashStep.PASO_4_NUDILLOS, t, 0); save(a); }

        assertEquals("PAUSADO", a.snapshot().estadoIntento());
        assertEquals("STEP_OUT_OF_SEQUENCE", a.snapshot().motivoRecuperacion());
        assertEquals(1, a.number());
        assertEquals(1, a.validated());
    }

    @Test void theWrongStepClockStartsOverWhenTheExpectedStepComesBack() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (long t = 8000; t <= 12750; t += 250) { a.observe(WashStep.PASO_4_NUDILLOS, t, 0); save(a); }
        a.observe(WashStep.PASO_1_PALMAS, 13000, 0); save(a);
        for (long t = 13250; t <= 17750; t += 250) { a.observe(WashStep.PASO_4_NUDILLOS, t, 0); save(a); }

        assertEquals("PAUSADO", a.snapshot().estadoIntento());
        assertEquals(1, a.number());
    }

    @Test void aGapInTheEvidenceDuringTheWrongStepDoesNotCountAsHeld() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        a.observe(WashStep.PASO_4_NUDILLOS, 8000, 0); save(a);
        a.observe(WashStep.PASO_4_NUDILLOS, 8250, 0); save(a);
        a.observe(WashStep.PASO_4_NUDILLOS, 12000, 0); save(a); // 3.75 s of silence: no proof it was held
        a.observe(WashStep.PASO_4_NUDILLOS, 12250, 0); save(a);

        assertNotEquals("INTERRUMPIDO", a.snapshot().estadoIntento());
        assertEquals(1, a.number());
    }

    @Test void theLegitimateNextMovementSeenSparselyIsNeverTreatedAsAMistake() {
        var a = attempt();
        dwell(a, WashStep.PASO_1_PALMAS, 1000, 6);
        for (long t = 8000; t <= 13000; t += 1000) { a.observe(WashStep.PASO_2_DORSOS, t, 0); save(a); }

        assertNotEquals("INTERRUMPIDO", a.snapshot().estadoIntento());
        assertEquals(1, a.number());
        assertEquals(1, a.validated());
    }

    // ---- quality of each step, final score and flexible progression ----
    private long dwellWith(BalancedFrictionAttempt a, WashStep step, long start, int seconds, float confidence) {
        for (int i = 0; i <= seconds; i++) { a.observe(step, start + i * 1000L, 0, confidence); save(a); }
        return start + seconds * 1000L;
    }

    private BalancedFrictionAttempt fullWash(float confidence, int secondsPerStep) {
        var a = attempt();
        long now = dwellWith(a, BalancedFrictionStrategy.STEPS.get(0), 1000, secondsPerStep, confidence);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0, confidence);
            a.observe(step, now += 200, 0, confidence);
            now = dwellWith(a, step, now + 1000, secondsPerStep, confidence);
        }
        return a;
    }

    @Test void aGoodWashScoresHighAndIsCorrect() {
        var a = fullWash(0.95f, 8);
        var result = a.snapshot().ultimoResultado();
        assertEquals("FRICTION_SEQUENCE_COMPLETE", result.resultado());
        assertTrue(result.puntajePct() >= 90, "score was " + result.puntajePct());
        assertEquals("CORRECTO", result.veredicto());
        assertTrue(result.pasos().stream().allMatch(p -> p.calidadPct() >= 90));
        assertEquals(result.puntajePct(), a.snapshot().puntajePct());
        assertEquals("CORRECTO", a.snapshot().veredicto());
    }

    @Test void aWashWhereTheDetectorWasNeverSureIsRejected() {
        var a = fullWash(0.55f, 8);
        var result = a.snapshot().ultimoResultado();
        assertTrue(result.puntajePct() < 80, "score was " + result.puntajePct());
        assertEquals("RECHAZADO", result.veredicto());
    }

    @Test void thePassMarkIsEightyPercentOfTheAverageOfTheSixSteps() {
        var a = fullWash(0.80f, 8);
        var r = a.snapshot().ultimoResultado();
        assertEquals(80, r.puntajePct(), 1);
        assertEquals(r.puntajePct() >= 80 ? "CORRECTO" : "RECHAZADO", r.veredicto());
    }

    @Test void theRunningScoreAndPerStepQualityAreVisibleWhileWashing() {
        var a = attempt();
        dwellWith(a, WashStep.PASO_1_PALMAS, 1000, 3, 0.9f);   // about half of the time asked
        var first = a.snapshot().pasos().get(0);
        assertTrue(first.calidadPct() > 0 && first.calidadPct() < 80);
        assertNull(a.snapshot().veredicto(), "no verdict before the wash is over");
        assertTrue(a.snapshot().puntajePct() > 0);
    }

    @Test void movingOnAfterMostOfTheTimeIsAcceptedWithAnHonestLowerQuality() {
        var a = attempt();
        dwellWith(a, WashStep.PASO_1_PALMAS, 1000, 4, 0.95f);   // 4 s of 6 asked
        a.observe(WashStep.PASO_2_DORSOS, 5200, 0, 0.95f);
        a.observe(WashStep.PASO_2_DORSOS, 5400, 0, 0.95f);
        assertEquals(WashStep.PASO_2_DORSOS, a.expected(), "the wash moved on instead of stopping the person");
        assertEquals(VALIDADO, a.snapshot().pasos().get(0).estado());
        int quality = a.snapshot().pasos().get(0).calidadPct();
        assertTrue(quality < 80 && quality > 40, "quality was " + quality);
    }

    @Test void movingOnTooEarlyAsksToGoBackAndDoesNotRestartForALongWhile() {
        var a = attempt();
        dwellWith(a, WashStep.PASO_1_PALMAS, 1000, 1, 0.95f);   // far from enough
        a.observe(WashStep.PASO_2_DORSOS, 2200, 0, 0.95f);
        a.observe(WashStep.PASO_2_DORSOS, 2400, 0, 0.95f);
        assertEquals("STEP_TOO_SHORT", a.snapshot().motivoRecuperacion());
        for (long t = 2600; t <= 12000; t += 250) a.observe(WashStep.PASO_2_DORSOS, t, 0, 0.95f);
        assertNotEquals("INTERRUMPIDO", a.snapshot().estadoIntento(), "a premature next step is guided, not punished");
        assertEquals(WashStep.PASO_1_PALMAS, a.expected());
        dwellWith(a, WashStep.PASO_1_PALMAS, 12500, 6, 0.95f);   // the person goes back and finishes it
        assertEquals(VALIDADO, a.snapshot().pasos().get(0).estado());
    }

    @Test void aRealJumpOutOfOrderStillRestartsAfterSixSeconds() {
        var a = attempt();
        dwellWith(a, WashStep.PASO_1_PALMAS, 1000, 7, 0.95f);
        for (long t = 9000; t <= 15500; t += 250) { a.observe(WashStep.PASO_5_PULGAR, t, 0, 0.95f); save(a); }
        assertEquals("INTERRUMPIDO", a.snapshot().estadoIntento());
        assertEquals("SEQUENCE_RESTART", a.snapshot().ultimoResultado().motivo());
    }

    @Test void takingTheHandsAwayAfterMostOfTheLastStepEndsTheWashWithItsScore() {
        var a = attempt();
        long now = dwellWith(a, BalancedFrictionStrategy.STEPS.get(0), 1000, 8, 0.95f);
        for (int i = 1; i < 6; i++) {
            var step = BalancedFrictionStrategy.STEPS.get(i);
            a.observe(step, now += 200, 0, 0.95f);
            a.observe(step, now += 200, 0, 0.95f);
            now = dwellWith(a, step, now + 1000, i < 5 ? 8 : 1, 0.95f);   // the last one only a bit more than half
        }
        assertNotEquals("COMPLETO_PARCIAL", a.snapshot().estadoIntento());
        assertTrue(a.snapshot().pasos().get(5).tiempoAcreditadoMs() >= 2000, "credited " + a.snapshot().pasos().get(5).tiempoAcreditadoMs() + " expected " + a.expected());
        a.presence(0, now + 500, 500); save(a);
        a.presence(0, now + 1000, 500); save(a);
        a.presence(0, now + 1500, 500); save(a);
        a.presence(0, now + 2000, 500); save(a);
        a.presence(0, now + 2500, 500); save(a);
        var result = a.snapshot().ultimoResultado();
        assertEquals("FRICTION_SEQUENCE_COMPLETE", result.resultado());
        assertNotNull(result.veredicto());
        assertTrue(result.pasos().get(5).calidadPct() < 90);
    }
}
