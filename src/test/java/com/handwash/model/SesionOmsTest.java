package com.handwash.model;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SesionOmsTest {
    private static final long GAP_MS = 800L;

    @Test
    void invalidMonotonicTimestampCannotStartAnOmsAttempt() {
        SesionOms session = new SesionOms("oms-invalid-time", false, 1_500L);

        EstadoLavadoResponse response = session.procesar(
            AccionOms.MOJAR_MANOS, Map.of(), 0L, 0.95f);

        assertNull(session.getCurrentAction());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0, session.getCurrentAttempt());
        assertEquals(Boolean.FALSE, response.getManoDetectada());
        assertEquals(0.0f, response.getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void invalidConfidenceCannotBridgeOrAdvanceAnActiveOmsPhase() {
        SesionOms session = new SesionOms("oms-invalid-confidence", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.95f);

        EstadoLavadoResponse rejected = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 11_600L, Float.NaN);

        assertNull(session.getCurrentAction());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0L, session.getTotalActiveMs());
        assertEquals(1, session.getRetries());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            rejected.getUltimoErrorReinicio().getTipo());
        assertEquals(Boolean.FALSE, rejected.getManoDetectada());
    }

    @Test
    void completeSequenceRequiresAllSoapRegionsAndConfiguredDuration() {
        SesionOms session = new SesionOms("oms-valid", true, 1_500L, 40_000L);
        long timestamp = 10_000L;

        for (AccionOms action : AccionOms.SECUENCIA) {
            Map<String, EvidenciaJabon> evidence = foamFor(action);
            for (int observation = 0; observation < 8; observation++) {
                session.procesar(action, evidence, timestamp, 0.95f);
                timestamp += GAP_MS;
            }
        }

        assertEquals(EstadoSesion.COMPLETADA, session.getStatus());
        assertTrue(session.isSoapCoverageComplete());
        assertTrue(session.isProcedureValidated());
        assertTrue(session.getTotalActiveMs() >= 40_000L);
        assertEquals(Boolean.FALSE, session.getEstadoActualResponse().getManoDetectada());
        assertEquals(0.0f, session.getEstadoActualResponse().getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void anyOutOfOrderActionRestartsAndRepeatedRiskAfterResetDoesNotCountPhantomAttempts() {
        SesionOms session = new SesionOms("oms-retry", false, 1_500L);
        long timestamp = 20_000L;
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp + GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_PALMAS, Map.of(), timestamp + 2 * GAP_MS, 0.95f);

        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction(),
            "un solo salto no debe reiniciar el intento OMS");
        assertEquals(0, session.getRetries());
        session.procesar(AccionOms.FROTAR_PALMAS, Map.of(), timestamp + 3 * GAP_MS, 0.95f);

        assertNull(session.getCurrentAction());
        assertEquals(1, session.getRetries());
        assertEquals(1, session.getAttempts().size());
        assertEquals("REINICIADO", session.getAttempts().get(0).resultado());
        assertEquals(TipoInfraccion.ACCION_OMS_FUERA_DE_SECUENCIA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());

        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp + 4 * GAP_MS, 0.95f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp + 5 * GAP_MS, 0.95f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(TipoInfraccion.ACCION_OMS_FUERA_DE_SECUENCIA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo(),
            "El motivo debe seguir en el caché al comenzar de nuevo");

        session.procesar(AccionOms.CONTACTO_RIESGO, Map.of(), timestamp + 6 * GAP_MS, 0.99f);
        session.procesar(AccionOms.CONTACTO_RIESGO, Map.of(), timestamp + 7 * GAP_MS, 0.99f);
        assertEquals(2, session.getRetries(), "Un riesgo sin intento activo no debe sumar reintentos falsos");
        assertEquals(TipoInfraccion.CONTACTO_RIESGO,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
    }

    @Test
    void restartingAfterOutOfOrderOmsActionClearsLiveConfidenceAndHandPresence() {
        SesionOms session = new SesionOms("oms-stale-restart", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.95f);

        EstadoLavadoResponse candidate = session.procesar(
            AccionOms.FROTAR_PALMAS, Map.of(), 11_600L, 0.99f);
        assertEquals(EstadoSesion.EN_PROGRESO, session.getStatus());
        assertEquals(Boolean.TRUE, candidate.getManoDetectada());
        assertEquals(0, candidate.getIntentosReiniciados());

        EstadoLavadoResponse restarted = session.procesar(
            AccionOms.FROTAR_PALMAS, Map.of(), 12_400L, 0.99f);

        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(Boolean.FALSE, restarted.getManoDetectada());
        assertEquals(0.0f, restarted.getConfianzaDeteccion(), 0.0001f);
        assertEquals(1, restarted.getIntentosReiniciados());
    }

    @Test
    void expiringOmsSessionClearsLiveConfidenceAndHandPresence() {
        SesionOms session = new SesionOms("oms-stale-expiry", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);
        assertEquals(Boolean.TRUE, session.getEstadoActualResponse().getManoDetectada());

        session.expire();

        EstadoLavadoResponse expired = session.getEstadoActualResponse();
        assertEquals(EstadoSesion.EXPIRADA, session.getStatus());
        assertEquals(Boolean.FALSE, expired.getManoDetectada());
        assertEquals(0.0f, expired.getConfianzaDeteccion(), 0.0001f);
    }

    @Test
    void inactivityExpiryArchivesAnActiveOmsAttemptBeforeClearingLiveEvidence() {
        SesionOms session = new SesionOms("oms-expiry-attempt", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.95f);

        session.expire();

        assertEquals(EstadoSesion.EXPIRADA, session.getStatus());
        assertEquals(1, session.getAttempts().size());
        IntentoLavadoResumen attempt = session.getAttempts().get(0);
        assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD", attempt.motivoReinicio());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            attempt.infracciones().get(0).getTipo());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            session.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
        assertEquals(Boolean.FALSE, session.getEstadoActualResponse().getManoDetectada());
    }

    @Test
    void missingSoapRegionAndTooShortTotalDurationFailClosedAndRestart() {
        SesionOms soapSession = new SesionOms("oms-soap-missing", true, 1_500L);
        long timestamp = 30_000L;
        soapSession.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        soapSession.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        for (int observation = 0; observation < 2; observation++) {
            soapSession.procesar(AccionOms.APLICAR_JABON, Map.of(), timestamp += GAP_MS, 0.95f);
        }
        for (AccionOms action : AccionOms.SECUENCIA.subList(2, 8)) {
            Map<String, EvidenciaJabon> evidence = foamFor(action);
            if (action == AccionOms.FROTAR_PUNTAS_DE_DEDOS) {
                evidence.remove(RegionJabon.PUNTAS_DE_DEDOS_DERECHA.name());
            }
            soapSession.procesar(action, evidence, timestamp += GAP_MS, 0.95f);
            soapSession.procesar(action, evidence, timestamp += GAP_MS, 0.95f);
        }
        soapSession.procesar(AccionOms.ENJUAGAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        soapSession.procesar(AccionOms.ENJUAGAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertNull(soapSession.getCurrentAction());
        assertFalse(soapSession.isSoapCoverageComplete());
        assertEquals(1, soapSession.getRetries());

        SesionOms shortSession = new SesionOms("oms-short", true, 1_500L, 40_000L);
        timestamp = 40_000L;
        for (AccionOms action : AccionOms.SECUENCIA) {
            Map<String, EvidenciaJabon> evidence = foamFor(action);
            for (int observation = 0; observation < 3; observation++) {
                shortSession.procesar(action, evidence, timestamp, 0.95f);
                timestamp += GAP_MS;
                if (action == AccionOms.CERRAR_GRIFO_CON_TOALLA
                    && shortSession.getCurrentAction() == null) break;
            }
        }
        assertNotEquals(EstadoSesion.COMPLETADA, shortSession.getStatus());
        assertEquals(1, shortSession.getRetries());
        assertEquals(TipoInfraccion.DURACION_OMS_INSUFICIENTE,
            shortSession.getCurrentInfraction().getTipo());
        assertFalse(shortSession.isProcedureValidated());
    }

    @Test
    void omsDurationTargetComesFromSelectedJavaStrategy() {
        SesionLavado domestic = new SesionLavado("oms-domestic-strategy", TipoProtocolo.DOMESTICO);
        SesionLavado clinical = new SesionLavado("oms-clinical-strategy", TipoProtocolo.CLINICO_QUIRURGICO);

        assertEquals(40_000L, domestic.procesarAccionOms(
            AccionOms.MOJAR_MANOS, Map.of(), 50_000L, 0.9f).getDuracionMinimaObjetivoMs());
        assertEquals(60_000L, clinical.procesarAccionOms(
            AccionOms.MOJAR_MANOS, Map.of(), 50_000L, 0.9f).getDuracionMinimaObjetivoMs());
    }

    @Test
    void cameraGapRestartsOmsAttemptAndForgetsCoverage() {
        SesionOms session = new SesionOms("oms-camera-gap", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        long lastActivity = session.getLastActivityMs();

        EstadoLavadoResponse gap = session.procesar(
            AccionOms.SIN_EVIDENCIA, Map.of(), 11_000L, 1.0f);
        assertEquals(0L, gap.getTiempoTotalActivoMs());
        assertEquals(Boolean.FALSE, gap.getManoDetectada());
        assertEquals(lastActivity, session.getLastActivityMs());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
            gap.getInfraccion().getTipo());
        assertEquals(1, session.getRetries());

        session.procesar(AccionOms.SIN_EVIDENCIA, Map.of(), 11_600L, 1.0f);
        assertEquals(1, session.getRetries(), "Los frames vacíos repetidos no son nuevos intentos");
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 12_400L, 0.9f);
        assertEquals(EstadoSesion.EN_PROGRESO, session.getStatus());
        assertEquals(2, session.getCurrentAttempt());
    }

    @Test
    void silentDetectorGapCannotContinueOrCompleteTheSameOmsAttempt() {
        SesionOms session = new SesionOms("oms-silent-gap", true, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        assertEquals(800L, session.getTotalActiveMs());

        EstadoLavadoResponse lost = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 12_301L, 0.9f);

        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertNull(session.getCurrentAction());
        assertEquals(0L, session.getTotalActiveMs());
        assertEquals(1, session.getRetries());
        assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA, lost.getInfraccion().getTipo());
        assertEquals(Boolean.FALSE, lost.getManoDetectada());
        assertFalse(session.isProcedureValidated());

        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 12_500L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(2, session.getCurrentAttempt());
    }

    @Test
    void riskContactAfterVisualGapIsNotHiddenByGapRecovery() {
        SesionOms session = new SesionOms("oms-risk-after-gap", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        EstadoLavadoResponse risk = session.procesar(
            AccionOms.CONTACTO_RIESGO, Map.of(), 12_301L, 0.95f);

        assertEquals(TipoInfraccion.CONTACTO_RIESGO, risk.getInfraccion().getTipo());
        assertEquals(TipoInfraccion.CONTACTO_RIESGO,
            risk.getUltimoErrorReinicio().getTipo());
        assertEquals("CONTACTO_RIESGO", session.getAttempts().get(0).motivoReinicio());
        assertEquals(1, session.getRetries());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
    }

    @Test
    void nextActionNeedsTwoObservationsAndCreditsTheTransitionIntervalToTheNewPhase() {
        SesionOms session = new SesionOms("oms-transition-closes-phase", true,
            1_000L, 40_000L, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        EstadoLavadoResponse firstCandidate = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(EstadoSesion.EN_PROGRESO, session.getStatus());
        assertEquals(800L, session.getActiveMsByAction().get(AccionOms.MOJAR_MANOS.getClaseModelo()));
        assertEquals(AccionOms.MOJAR_MANOS.getClaseModelo(), firstCandidate.getEstadoActual());
        assertEquals(AccionOms.APLICAR_JABON.getClaseModelo(), firstCandidate.getClaseCandidata());
        assertEquals(0.9f, firstCandidate.getConfianzaCandidata());
        assertNull(firstCandidate.getInfraccion());
        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction(),
            "el mismo timestamp no cuenta como una segunda observación");

        EstadoLavadoResponse transition = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertEquals(AccionOms.APLICAR_JABON, session.getCurrentAction());
        assertEquals(AccionOms.APLICAR_JABON.getClaseModelo(), transition.getEstadoActual());
        assertEquals(EstadoSesion.EN_PROGRESO, session.getStatus());
        assertEquals(1_600L, session.getActiveMsByAction().get(AccionOms.MOJAR_MANOS.getClaseModelo()));
        assertEquals(800L, session.getActiveMsByAction().get(AccionOms.APLICAR_JABON.getClaseModelo()));
        assertNull(transition.getClaseCandidata());
        assertNull(transition.getInfraccion());
    }

    @Test
    void oneTransientNextActionCannotAdvanceOrResetAnOmsPhase() {
        SesionOms session = new SesionOms("oms-single-phase-observation", true,
            1_500L, 40_000L, 2_000L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        EstadoLavadoResponse firstCandidate = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(0, firstCandidate.getIntentosReiniciados());

        EstadoLavadoResponse transition = session.procesar(
            AccionOms.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertNull(session.getCurrentAction());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(1, session.getRetries());
        assertEquals(TipoInfraccion.FASE_OMS_DEMASIADO_CORTA,
            transition.getUltimoErrorReinicio().getTipo());
        assertEquals(1, session.getAttempts().size());
    }

    @Test
    void returningToCurrentActionCancelsThePendingOmsTransitionVote() {
        SesionOms session = new SesionOms("oms-transition-cancel", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        EstadoLavadoResponse currentPhase = session.procesar(
            AccionOms.MOJAR_MANOS, Map.of(), 12_000L, 0.9f);

        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());
        assertEquals(0, currentPhase.getIntentosReiniciados());
        assertEquals(2_000L, session.getActiveMsByAction().get(AccionOms.MOJAR_MANOS.getClaseModelo()));

        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 12_400L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction(),
            "el candidato anterior no cuenta al iniciar una nueva votación");
        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 13_200L, 0.9f);
        assertEquals(AccionOms.APLICAR_JABON, session.getCurrentAction());
    }

    @Test
    void differentCandidateActionsCannotCombineTheirTransitionVotes() {
        SesionOms session = new SesionOms("oms-candidate-switch", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);

        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        session.procesar(AccionOms.FROTAR_PALMAS, Map.of(), 12_000L, 0.9f);
        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 12_400L, 0.9f);
        assertEquals(AccionOms.MOJAR_MANOS, session.getCurrentAction());

        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 13_200L, 0.9f);
        assertEquals(AccionOms.APLICAR_JABON, session.getCurrentAction());
    }

    @Test
    void omsProgressErrorsAndCachedAttemptsUseCanonicalWireCodes() {
        SesionOms session = new SesionOms("oms-wire-codes", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_800L, 0.9f);
        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 11_600L, 0.9f);
        session.procesar(AccionOms.APLICAR_JABON, Map.of(), 12_400L, 0.9f);

        assertEquals(AccionOms.APLICAR_JABON.getClaseModelo(),
            session.getEstadoActualResponse().getEstadoActual());
        assertEquals(1_600L, session.getActiveMsByAction().get(AccionOms.MOJAR_MANOS.getClaseModelo()));
        assertEquals(800L, session.getActiveMsByAction().get(AccionOms.APLICAR_JABON.getClaseModelo()));
        assertTrue(session.getMissingActions().contains(AccionOms.FROTAR_PALMAS.getClaseModelo()));

        session.registrarError(TipoInfraccion.ERROR_PROCESAMIENTO,
            "Fallo de procesamiento", AccionOms.APLICAR_JABON.name());
        assertEquals(AccionOms.APLICAR_JABON.getClaseModelo(),
            session.getCurrentInfraction().getPaso());
        session.reiniciarIntento("ERROR_PROCESAMIENTO");

        IntentoLavadoResumen failedAttempt = session.getAttempts().get(0);
        assertTrue(failedAttempt.tiempoPorPasoMs().containsKey(AccionOms.MOJAR_MANOS.getClaseModelo()));
        assertTrue(failedAttempt.tiempoPorPasoMs().containsKey(AccionOms.APLICAR_JABON.getClaseModelo()));
        assertEquals(AccionOms.APLICAR_JABON.getClaseModelo(),
            failedAttempt.infracciones().get(0).getPaso());
    }

    @Test
    void soapObservedDuringApplicationCannotBypassMissingBilateralRubbingCoverage() {
        SesionOms session = new SesionOms("oms-per-phase-soap", true, 1_500L);
        long timestamp = 10_000L;
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp, 0.95f);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.APLICAR_JABON, fullFoamEvidence(), timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.APLICAR_JABON, fullFoamEvidence(), timestamp += GAP_MS, 0.95f);
        assertFalse(session.isSoapCoverageComplete());
        assertEquals("NO_VERIFICABLE", session.getCoverageStates().get("PALMA_DERECHA"));

        Map<String, EvidenciaJabon> onlyLeftPalm = Map.of(
            RegionJabon.PALMA_IZQUIERDA.name(),
            new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f));
        session.procesar(AccionOms.FROTAR_PALMAS, onlyLeftPalm, timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_PALMAS, onlyLeftPalm, timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertNull(session.getCurrentAction());
        assertEquals(1, session.getRetries());
        assertEquals(TipoInfraccion.COBERTURA_JABON_INCOMPLETA,
            session.getCurrentInfraction().getTipo());
    }

    @Test
    void cachedFailedAttemptKeepsOnlyBoundedErrorsInMemory() {
        SesionOms session = new SesionOms("oms-bounded-cache", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        for (int index = 0; index < 80; index++) {
            session.registrarError(TipoInfraccion.ERROR_PROCESAMIENTO,
                "Error de prueba " + index, AccionOms.MOJAR_MANOS.name());
        }
        session.reiniciarIntento("REINTENTO_POR_ERROR");

        assertEquals(50, session.getAttempts().get(0).infracciones().size());
        assertEquals("Error de prueba 79",
            session.getEstadoActualResponse().getUltimoErrorReinicio().getDetalle());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(0L, session.getTotalActiveMs());
    }

    @Test
    void globalInfractionDeduplicationDoesNotEraseTheSameErrorFromANewAttempt() {
        SesionOms session = new SesionOms("oms-attempt-audit-dedup", false, 1_500L);
        String detail = "Observación inválida durante el lavado";

        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        session.registrarError(TipoInfraccion.ERROR_PROCESAMIENTO, detail,
            AccionOms.MOJAR_MANOS.name());
        session.registrarError(TipoInfraccion.ERROR_PROCESAMIENTO, detail,
            AccionOms.MOJAR_MANOS.name());
        session.reiniciarIntento("ERROR_PROCESAMIENTO");

        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 11_000L, 0.9f);
        session.registrarError(TipoInfraccion.ERROR_PROCESAMIENTO, detail,
            AccionOms.MOJAR_MANOS.name());
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
        SesionOms session = new SesionOms("oms-monotonic-idle", false, 1_500L);
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
        SesionOms session = new SesionOms("oms-monotonic-duration", false, 1_500L);
        Thread.sleep(5L);
        session.expire();
        long sessionStartMs = (long) ReflectionTestUtils.getField(session, "sessionStartMs");
        ReflectionTestUtils.setField(session, "finishedAtMs", sessionStartMs - 1_000L);

        assertTrue(session.getDurationMs() >= 1L);
    }

    @Test
    void failedOmsAttemptHistoryDurationUsesMonotonicTime() {
        SesionOms session = new SesionOms("oms-failed-attempt-duration", false, 1_500L);
        session.procesar(AccionOms.MOJAR_MANOS, Map.of(), 10_000L, 0.9f);
        ReflectionTestUtils.setField(session, "attemptStartMonotonicNanos",
            System.nanoTime() - TimeUnit.SECONDS.toNanos(2));

        session.reiniciarIntento("test");

        long durationMs = session.getAttempts().get(0).duracionMs();
        assertTrue(durationMs >= 1_500L, "el resumen debe usar el inicio monotónico del intento OMS");
        assertTrue(durationMs < 10_000L, "la duración del intento debe permanecer acotada");
    }

    @Test
    void contradictorySoapEvidenceRevokesEarlierCreditBeforeAdvancing() {
        SesionOms session = new SesionOms("oms-contradiction", true, 1_500L);
        long timestamp = 10_000L;
        for (AccionOms action : AccionOms.SECUENCIA.subList(0, 2)) {
            session.procesar(action, Map.of(), timestamp += GAP_MS, 0.95f);
            session.procesar(action, Map.of(), timestamp += GAP_MS, 0.95f);
        }
        Map<String, EvidenciaJabon> palms = foamFor(AccionOms.FROTAR_PALMAS);
        session.procesar(AccionOms.FROTAR_PALMAS, palms, timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_PALMAS, palms, timestamp += GAP_MS, 0.95f);
        assertEquals("ESPUMA_VISIBLE", session.getCoverageStates().get("PALMA_IZQUIERDA"));

        session.procesar(AccionOms.FROTAR_PALMAS, Map.of(
            RegionJabon.PALMA_IZQUIERDA.name(),
            new EvidenciaJabon(EstadoEvidenciaJabon.NO_VERIFICABLE, 0.95f)
        ), timestamp += GAP_MS, 0.95f);
        assertEquals("NO_VERIFICABLE", session.getCoverageStates().get("PALMA_IZQUIERDA"));

        session.procesar(AccionOms.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        session.procesar(AccionOms.FROTAR_DORSOS, Map.of(), timestamp += GAP_MS, 0.95f);
        assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getStatus());
        assertEquals(TipoInfraccion.COBERTURA_JABON_INCOMPLETA,
            session.getCurrentInfraction().getTipo());
    }

    private Map<String, EvidenciaJabon> fullFoamEvidence() {
        Map<String, EvidenciaJabon> evidence = new LinkedHashMap<>();
        for (RegionJabon region : RegionJabon.values()) {
            evidence.put(region.name(), new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f));
        }
        return evidence;
    }

    private Map<String, EvidenciaJabon> foamFor(AccionOms action) {
        Map<String, EvidenciaJabon> evidence = new LinkedHashMap<>();
        for (RegionJabon region : RegionJabon.values()) {
            if (region.getAccionVerificacion() == action) {
                evidence.put(region.name(), new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f));
            }
        }
        return evidence;
    }
}
