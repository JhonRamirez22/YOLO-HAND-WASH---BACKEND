package com.handwash.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WhoSessionTest {
    private static final long GAP_MS = 800L;

    @Test
    void invalidMonotonicTimestampCannotStartAnOmsAttempt() {
        WhoSession session = new WhoSession("oms-invalid-time", false, 1_500L);

        WashStateResponse response = session.procesar(
            WhoAction.MOJAR_MANOS, Map.of(), 0L, 0.95f);

        assertNull(session.getCurrentAction());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0, session.getCurrentAttempt());
        assertEquals(Boolean.FALSE, response.getManoDetectada());
        assertEquals(0.0f, response.getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void invalidConfidenceCannotBridgeOrAdvanceAnActiveOmsPhase() {
        WhoSession session = new WhoSession("oms-invalid-confidence", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.95f);

        WashStateResponse rejected = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 11_600L, Float.NaN);

        assertNull(session.getCurrentAction());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0L, session.getTotalActiveMs());
        assertEquals(1, session.getRetries());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            rejected.getUltimoErrorReinicio().getTipo());
        assertEquals(Boolean.FALSE, rejected.getManoDetectada());
    }

    @Test
    void completeSequenceRequiresAllSoapRegionsAndConfiguredDuration() {
        WhoSession session = new WhoSession("oms-valid", true, 1_500L, 40_000L);
        long timestamp = 10_000L;

        for (WhoAction action : WhoAction.SECUENCIA) {
            Map<String, SoapEvidence> evidence = foamFor(action);
            for (int observation = 0; observation < 8; observation++) {
                session.procesar(action, evidence, timestamp, 0.95f);
                timestamp += GAP_MS;
            }
        }

        assertEquals(SessionStatus.COMPLETADA, session.getStatus());
        assertTrue(session.isSoapCoverageComplete());
        assertTrue(session.isProcedureValidated());
        assertTrue(session.getTotalActiveMs() >= 40_000L);
        assertEquals(Boolean.FALSE, session.getEstadoActualResponse().getManoDetectada());
        assertEquals(0.0f, session.getEstadoActualResponse().getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void anyOutOfOrderActionRestartsAndRepeatedRiskAfterResetDoesNotCountPhantomAttempts() {
        WhoSession session = new WhoSession("oms-retry", false, 1_500L);
        long timestamp = 20_000L;
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp + GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_PALMAS, Map.of(), timestamp + 2 * GAP_MS, 0.95f);

        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction(),
            "un solo salto no debe reiniciar el intento OMS");
        assertEquals(0, session.getRetries());
        session.procesar(WhoAction.FROTAR_PALMAS, Map.of(), timestamp + 3 * GAP_MS, 0.95f);

        assertNull(session.getCurrentAction());
        assertEquals(1, session.getRetries());
        assertEquals(1, session.getAttempts().size());
        assertEquals("REINICIADO", session.getAttempts().get(0).resultado());
        assertEquals(InfractionType.ACCION_OMS_FUERA_DE_SECUENCIA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());

        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp + 4 * GAP_MS, 0.95f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp + 5 * GAP_MS, 0.95f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(InfractionType.ACCION_OMS_FUERA_DE_SECUENCIA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo(),
            "El motivo debe seguir en el caché al comenzar de nuevo");

        session.procesar(WhoAction.CONTACTO_RIESGO, Map.of(), timestamp + 6 * GAP_MS, 0.99f);
        session.procesar(WhoAction.CONTACTO_RIESGO, Map.of(), timestamp + 7 * GAP_MS, 0.99f);
        assertEquals(2, session.getRetries(), "Un riesgo sin intento activo no debe sumar reintentos falsos");
        assertEquals(InfractionType.CONTACTO_RIESGO,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
    }

    @Test
    void restartingAfterOutOfOrderOmsActionClearsLiveConfidenceAndHandPresence() {
        WhoSession session = new WhoSession("oms-stale-restart", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.95f);

        WashStateResponse candidate = session.procesar(
            WhoAction.FROTAR_PALMAS, Map.of(), 11_600L, 0.99f);
        assertEquals(SessionStatus.EN_PROGRESO, session.getStatus());
        assertEquals(Boolean.TRUE, candidate.getManoDetectada());
        assertEquals(0, candidate.getIntentosReiniciados());

        WashStateResponse restarted = session.procesar(
            WhoAction.FROTAR_PALMAS, Map.of(), 12_400L, 0.99f);

        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(Boolean.FALSE, restarted.getManoDetectada());
        assertEquals(0.0f, restarted.getConfianzaDeteccion(), 0.0001f);
        assertEquals(1, restarted.getIntentosReiniciados());
    }

    @Test
    void expiringOmsSessionClearsLiveConfidenceAndHandPresence() {
        WhoSession session = new WhoSession("oms-stale-expiry", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        assertEquals(Boolean.TRUE, session.getEstadoActualResponse().getManoDetectada());

        session.expire();

        WashStateResponse expired = session.getEstadoActualResponse();
        assertEquals(SessionStatus.EXPIRADA, session.getStatus());
        assertEquals(Boolean.FALSE, expired.getManoDetectada());
        assertEquals(0.0f, expired.getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void inactivityExpiryArchivesAnActiveOmsAttemptBeforeClearingLiveEvidence() {
        WhoSession session = new WhoSession("oms-expiry-attempt", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);

        session.expire();

        assertEquals(SessionStatus.EXPIRADA, session.getStatus());
        assertEquals(1, session.getAttempts().size());
        WashAttemptSummary attempt = session.getAttempts().get(0);
        assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD", attempt.motivoReinicio());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            attempt.infracciones().get(0).getTipo());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
        assertEquals(Boolean.FALSE, session.getEstadoActualResponse().getManoDetectada());
    }

    @Test
    void missingSoapRegionAndTooShortTotalDurationFailClosedAndRestart() {
        WhoSession soapSession = new WhoSession("oms-soap-missing", true, 1_500L);
        long timestamp = 30_000L;
        soapSession.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        soapSession.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        for (int observation = 0; observation < 2; observation++) {
            soapSession.procesar(WhoAction.APLICAR_JABON, Map.of(), timestamp += GAP_MS, 0.95f);
        }
        for (WhoAction action : WhoAction.SECUENCIA.subList(2, 8)) {
            Map<String, SoapEvidence> evidence = foamFor(action);
            if (action == WhoAction.FROTAR_PUNTAS_DE_DEDOS) {
                evidence.remove(SoapRegion.PUNTAS_DE_DEDOS_DERECHA.name());
            }
            soapSession.procesar(action, evidence, timestamp += GAP_MS, 0.95f);
            soapSession.procesar(action, evidence, timestamp += GAP_MS, 0.95f);
        }
        soapSession.procesar(WhoAction.ENJUAGAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        soapSession.procesar(WhoAction.ENJUAGAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertNull(soapSession.getCurrentAction());
        assertFalse(soapSession.isSoapCoverageComplete());
        assertEquals(1, soapSession.getRetries());

        WhoSession shortSession = new WhoSession("oms-short", true, 1_500L, 40_000L);
        timestamp = 40_000L;
        for (WhoAction action : WhoAction.SECUENCIA) {
            Map<String, SoapEvidence> evidence = foamFor(action);
            for (int observation = 0; observation < 3; observation++) {
                shortSession.procesar(action, evidence, timestamp, 0.95f);
                timestamp += GAP_MS;
                if (action == WhoAction.CERRAR_GRIFO_CON_TOALLA
                    && shortSession.getCurrentAction() == null) break;
            }
        }
        assertNotEquals(SessionStatus.COMPLETADA, shortSession.getStatus());
        assertEquals(1, shortSession.getRetries());
        assertEquals(InfractionType.DURACION_OMS_INSUFICIENTE,
            shortSession.getCurrentInfraction().getTipo());
        assertFalse(shortSession.isProcedureValidated());
    }

    @Test
    void omsDurationTargetComesFromSelectedJavaStrategy() {
        WashSession domestic = new WashSession("oms-domestic-strategy", ProtocolType.DOMESTICO);
        WashSession clinical = new WashSession("oms-clinical-strategy", ProtocolType.CLINICO_QUIRURGICO);

        assertEquals(40_000L, domestic.procesarAccionOms(
            WhoAction.MOJAR_MANOS, Map.of(), 50_000L, 0.9f).getDuracionMinimaObjetivoMs());
        assertEquals(60_000L, clinical.procesarAccionOms(
            WhoAction.MOJAR_MANOS, Map.of(), 50_000L, 0.9f).getDuracionMinimaObjetivoMs());
    }

    @Test
    void cameraGapRestartsOmsAttemptAndForgetsCoverage() {
        WhoSession session = new WhoSession("oms-camera-gap", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        long lastActivity = session.getLastActivityMs();

        WashStateResponse gap = session.procesar(
            WhoAction.SIN_EVIDENCIA, Map.of(), 11_000L, 1.0f);
        assertEquals(0L, gap.getTiempoTotalActivoMs());
        assertEquals(Boolean.FALSE, gap.getManoDetectada());
        assertEquals(lastActivity, session.getLastActivityMs());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA,
            gap.getInfraccion().getTipo());
        assertEquals(1, session.getRetries());

        session.procesar(WhoAction.SIN_EVIDENCIA, Map.of(), 11_600L, 1.0f);
        assertEquals(1, session.getRetries(), "Los frames vacíos repetidos no son nuevos intentos");
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 12_400L, 0.9f);
        assertEquals(SessionStatus.EN_PROGRESO, session.getStatus());
        assertEquals(2, session.getCurrentAttempt());
    }

    @Test
    void silentDetectorGapCannotContinueOrCompleteTheSameOmsAttempt() {
        WhoSession session = new WhoSession("oms-silent-gap", true, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        assertEquals(800L, session.getTotalActiveMs());

        WashStateResponse lost = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 12_301L, 0.9f);

        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertNull(session.getCurrentAction());
        assertEquals(0L, session.getTotalActiveMs());
        assertEquals(1, session.getRetries());
        assertEquals(InfractionType.EVIDENCIA_VISUAL_INTERRUPTA, lost.getInfraccion().getTipo());
        assertEquals(Boolean.FALSE, lost.getManoDetectada());
        assertFalse(session.isProcedureValidated());

        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 12_500L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(2, session.getCurrentAttempt());
    }

    @Test
    void riskContactAfterVisualGapIsNotHiddenByGapRecovery() {
        WhoSession session = new WhoSession("oms-risk-after-gap", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        WashStateResponse risk = session.procesar(
            WhoAction.CONTACTO_RIESGO, Map.of(), 12_301L, 0.95f);

        assertEquals(InfractionType.CONTACTO_RIESGO, risk.getInfraccion().getTipo());
        assertEquals(InfractionType.CONTACTO_RIESGO,
            risk.getUltimoErrorReinicio().getTipo());
        assertEquals("CONTACTO_RIESGO", session.getAttempts().get(0).motivoReinicio());
        assertEquals(1, session.getRetries());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
    }

    @Test
    void nextActionNeedsTwoObservationsAndCreditsTheTransitionIntervalToTheNewPhase() {
        WhoSession session = new WhoSession("oms-transition-closes-phase", true,
            1_000L, 40_000L, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        WashStateResponse firstCandidate = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(SessionStatus.EN_PROGRESO, session.getStatus());
        assertEquals(800L, session.getActiveMsByAction().get(WhoAction.MOJAR_MANOS.getClaseModelo()));
        assertEquals(WhoAction.MOJAR_MANOS.getClaseModelo(), firstCandidate.getEstadoActual());
        assertEquals(WhoAction.APLICAR_JABON.getClaseModelo(), firstCandidate.getClaseCandidata());
        assertEquals(0.9f, firstCandidate.getConfianzaCandidata());
        assertNull(firstCandidate.getInfraccion());
        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction(),
            "el mismo timestamp no cuenta como una segunda observación");

        WashStateResponse transition = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertEquals(WhoAction.APLICAR_JABON, session.getCurrentAction());
        assertEquals(WhoAction.APLICAR_JABON.getClaseModelo(), transition.getEstadoActual());
        assertEquals(SessionStatus.EN_PROGRESO, session.getStatus());
        assertEquals(1_600L, session.getActiveMsByAction().get(WhoAction.MOJAR_MANOS.getClaseModelo()));
        assertEquals(800L, session.getActiveMsByAction().get(WhoAction.APLICAR_JABON.getClaseModelo()));
        assertNull(transition.getClaseCandidata());
        assertNull(transition.getInfraccion());
    }

    @Test
    void oneTransientNextActionCannotAdvanceOrResetAnOmsPhase() {
        WhoSession session = new WhoSession("oms-single-phase-observation", true,
            1_500L, 40_000L, 2_000L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        WashStateResponse firstCandidate = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(0, firstCandidate.getIntentosReiniciados());

        WashStateResponse transition = session.procesar(
            WhoAction.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertNull(session.getCurrentAction());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(1, session.getRetries());
        assertEquals(InfractionType.FASE_OMS_DEMASIADO_CORTA,
            transition.getUltimoErrorReinicio().getTipo());
        assertEquals(1, session.getAttempts().size());
    }

    @Test
    void returningToCurrentActionCancelsThePendingOmsTransitionVote() {
        WhoSession session = new WhoSession("oms-transition-cancel", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        WashStateResponse currentPhase = session.procesar(
            WhoAction.MOJAR_MANOS, Map.of(), 12_000L, 0.9f);

        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(0, currentPhase.getIntentosReiniciados());
        assertEquals(2_000L, session.getActiveMsByAction().get(WhoAction.MOJAR_MANOS.getClaseModelo()));

        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 12_400L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction(),
            "el candidato anterior no cuenta al iniciar una nueva votación");
        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 13_200L, 0.9f);
        assertEquals(WhoAction.APLICAR_JABON, session.getCurrentAction());
    }

    @Test
    void differentCandidateActionsCannotCombineTheirTransitionVotes() {
        WhoSession session = new WhoSession("oms-candidate-switch", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        session.procesar(WhoAction.FROTAR_PALMAS, Map.of(), 12_000L, 0.9f);
        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 12_400L, 0.9f);
        assertEquals(WhoAction.MOJAR_MANOS, session.getCurrentAction());

        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 13_200L, 0.9f);
        assertEquals(WhoAction.APLICAR_JABON, session.getCurrentAction());
    }

    @Test
    void omsProgressErrorsAndCachedAttemptsUseCanonicalWireCodes() {
        WhoSession session = new WhoSession("oms-wire-codes", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        session.procesar(WhoAction.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertEquals(WhoAction.APLICAR_JABON.getClaseModelo(),
            session.getEstadoActualResponse().getEstadoActual());
        assertEquals(1_600L, session.getActiveMsByAction().get(WhoAction.MOJAR_MANOS.getClaseModelo()));
        assertEquals(800L, session.getActiveMsByAction().get(WhoAction.APLICAR_JABON.getClaseModelo()));
        assertTrue(session.getMissingActions().contains(WhoAction.FROTAR_PALMAS.getClaseModelo()));

        session.registrarError(InfractionType.ERROR_PROCESAMIENTO,
            "Fallo de procesamiento", WhoAction.APLICAR_JABON.name());
        assertEquals(WhoAction.APLICAR_JABON.getClaseModelo(),
            session.getCurrentInfraction().getPaso());
        session.reiniciarIntento("ERROR_PROCESAMIENTO");

        WashAttemptSummary failedAttempt = session.getAttempts().get(0);
        assertTrue(failedAttempt.tiempoPorPasoMs().containsKey(WhoAction.MOJAR_MANOS.getClaseModelo()));
        assertTrue(failedAttempt.tiempoPorPasoMs().containsKey(WhoAction.APLICAR_JABON.getClaseModelo()));
        assertEquals(WhoAction.APLICAR_JABON.getClaseModelo(),
            failedAttempt.infracciones().get(0).getPaso());
    }

    @Test
    void soapObservedDuringApplicationCannotBypassMissingBilateralRubbingCoverage() {
        WhoSession session = new WhoSession("oms-per-phase-soap", true, 1_500L);
        long timestamp = 10_000L;
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.APLICAR_JABON, fullFoamEvidence(), timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.APLICAR_JABON, fullFoamEvidence(), timestamp += GAP_MS, 0.95f);
        assertFalse(session.isSoapCoverageComplete());
        assertEquals("NO_VERIFICABLE", session.getCoverageStates().get("PALMA_DERECHA"));

        Map<String, SoapEvidence> onlyLeftPalm = Map.of(
            SoapRegion.PALMA_IZQUIERDA.name(),
            new SoapEvidence(SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
        session.procesar(WhoAction.FROTAR_PALMAS, onlyLeftPalm, timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_PALMAS, onlyLeftPalm, timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertNull(session.getCurrentAction());
        assertEquals(1, session.getRetries());
        assertEquals(InfractionType.COBERTURA_JABON_INCOMPLETA,
            session.getCurrentInfraction().getTipo());
    }

    @Test
    void cachedFailedAttemptKeepsOnlyBoundedErrorsInMemory() {
        WhoSession session = new WhoSession("oms-bounded-cache", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        for (int index = 0; index < 80; index++) {
            session.registrarError(InfractionType.ERROR_PROCESAMIENTO,
                "Error de prueba " + index, WhoAction.MOJAR_MANOS.name());
        }
        session.reiniciarIntento("REINTENTO_POR_ERROR");

        assertEquals(50, session.getAttempts().get(0).infracciones().size());
        assertEquals("Error de prueba 79",
            session.getEstadoActualResponse().getUltimoErrorReinicio().getDetalle());
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0L, session.getTotalActiveMs());
    }

    @Test
    void globalInfractionDeduplicationDoesNotEraseTheSameErrorFromANewAttempt() {
        WhoSession session = new WhoSession("oms-attempt-audit-dedup", false, 1_500L);
        String detail = "Observación inválida durante el lavado";

        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.registrarError(InfractionType.ERROR_PROCESAMIENTO, detail,
            WhoAction.MOJAR_MANOS.name());
        session.registrarError(InfractionType.ERROR_PROCESAMIENTO, detail,
            WhoAction.MOJAR_MANOS.name());
        session.reiniciarIntento("ERROR_PROCESAMIENTO");

        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 11_000L, 0.9f);
        session.registrarError(InfractionType.ERROR_PROCESAMIENTO, detail,
            WhoAction.MOJAR_MANOS.name());
        session.reiniciarIntento("ERROR_PROCESAMIENTO");

        assertEquals(2, session.getAttempts().size());
        assertEquals(1, session.getAttempts().get(0).infracciones().size(),
            "frames repetidos del mismo intento se deduplican en su resumen");
        assertEquals(1, session.getAttempts().get(1).infracciones().size(),
            "un error de un intento nuevo debe conservarse aunque el historial global lo deduplique");
        assertEquals(detail, session.getAttempts().get(1).infracciones().get(0).getDetalle());
        assertEquals(1, session.getInfractions().size(),
            "el historial global sigue coalesciendo errores repetidos en la ventana de dos segundos");
    }

    @Test
    void omsInactivityExpiryUsesMonotonicElapsedTimeInsteadOfWallClock() {
        WhoSession session = new WhoSession("oms-monotonic-idle", false, 1_500L);
        long lastActivityNanos = (long) ReflectionTestUtils.getField(
            session, "lastActivityMonotonicNanos");
        long lastActivityEpochMs = session.getLastActivityMs();

        // Backwards wall-clock movement must not extend the configured timeout.
        long wallClockBehind = lastActivityEpochMs - TimeUnit.DAYS.toMillis(1);
        assertFalse(session.isInactiveSince(wallClockBehind,
            lastActivityNanos + TimeUnit.SECONDS.toNanos(59), 60_000L));
        assertTrue(session.isInactiveSince(wallClockBehind,
            lastActivityNanos + TimeUnit.SECONDS.toNanos(60), 60_000L));

        // A suspended machine counts the elapsed wall-clock time toward expiry.
        assertTrue(session.isInactiveSince(lastActivityEpochMs + TimeUnit.MINUTES.toMillis(2),
            lastActivityNanos + TimeUnit.SECONDS.toNanos(1), 60_000L));
    }

    @Test
    void omsSessionDurationDoesNotCollapseWhenWallClockMovesBackBeforeSessionStart()
            throws InterruptedException {
        WhoSession session = new WhoSession("oms-monotonic-duration", false, 1_500L);
        Thread.sleep(5L);
        session.expire();
        long sessionStartMs = (long) ReflectionTestUtils.getField(session, "sessionStartMs");
        ReflectionTestUtils.setField(session, "finishedAtMs", sessionStartMs - 1_000L);

        assertTrue(session.getDurationMs() >= 1L);
    }

    @Test
    void failedOmsAttemptHistoryDurationUsesMonotonicTime() {
        WhoSession session = new WhoSession("oms-failed-attempt-duration", false, 1_500L);
        session.procesar(WhoAction.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        ReflectionTestUtils.setField(session, "attemptStartMonotonicNanos",
            System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        session.reiniciarIntento("test");

        long durationMs = session.getAttempts().get(0).duracionMs();
        assertTrue(durationMs >= 1_500L, "el resumen debe usar el inicio monotónico del intento OMS");
        assertTrue(durationMs < 10_000L, "la duración del intento debe permanecer acotada");
    }

    @Test
    void contradictorySoapEvidenceRevokesEarlierCreditBeforeAdvancing() {
        WhoSession session = new WhoSession("oms-contradiction", true, 1_500L);
        long timestamp = 10_000L;
        for (WhoAction action : WhoAction.SECUENCIA.subList(0, 2)) {
            session.procesar(action, Map.of(), timestamp += GAP_MS, 0.95f);
            session.procesar(action, Map.of(), timestamp += GAP_MS, 0.95f);
        }
        Map<String, SoapEvidence> palms = foamFor(WhoAction.FROTAR_PALMAS);
        session.procesar(WhoAction.FROTAR_PALMAS, palms, timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_PALMAS, palms, timestamp += GAP_MS, 0.95f);
        assertEquals("ESPUMA_VISIBLE", session.getCoverageStates().get("PALMA_IZQUIERDA"));

        session.procesar(WhoAction.FROTAR_PALMAS, Map.of(
            SoapRegion.PALMA_IZQUIERDA.name(),
            new SoapEvidence(SoapEvidenceStatus.NO_VERIFICABLE, 0.95f)
        ), timestamp += GAP_MS, 0.95f);
        assertEquals("NO_VERIFICABLE", session.getCoverageStates().get("PALMA_IZQUIERDA"));

        session.procesar(WhoAction.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(WhoAction.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertEquals(SessionStatus.ESPERANDO_INICIO, session.getStatus());
        assertEquals(InfractionType.COBERTURA_JABON_INCOMPLETA,
            session.getCurrentInfraction().getTipo());
    }

    private Map<String, SoapEvidence> fullFoamEvidence() {
        Map<String, SoapEvidence> evidence = new LinkedHashMap<>();
        for (SoapRegion region : SoapRegion.values()) {
            evidence.put(region.name(), new SoapEvidence(SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
        }
        return evidence;
    }

    private Map<String, SoapEvidence> foamFor(WhoAction action) {
        Map<String, SoapEvidence> evidence = new LinkedHashMap<>();
        for (SoapRegion region : SoapRegion.values()) {
            if (region.getAccionVerificacion() == action) {
                evidence.put(region.name(), new SoapEvidence(SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
            }
        }
        return evidence;
    }
}
