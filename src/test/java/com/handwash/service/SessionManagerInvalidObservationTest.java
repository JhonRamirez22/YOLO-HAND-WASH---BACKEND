package com.handwash.service;

import com.handwash.agent.Receiver;
import com.handwash.intention.HandwashingIntentChain;
import com.handwash.model.OmsAction;
import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandwashingSessionState;
import com.handwash.model.HandwashingAttemptSummary;
import com.handwash.model.HandwashingStep;
import com.handwash.model.HandwashingSession;
import com.handwash.model.ViolationType;
import com.handwash.model.ProtocolType;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerInvalidObservationTest {
    private static final HandwashingIntentChain INTENTION =
        new HandwashingIntentChain(650, 650, 3, 1500, 0.75);

    @Test
    void malformedObservationBreaksStartVotesAndConsumesItsFreshEvidenceSequence() {
        SessionManager manager = new SessionManager(new Receiver());
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = manager.getSesion(sessionId);
            assertFalse(evaluar(session, evento(sessionId, "Paso1_Palmas", 1_000, 1)));
            assertFalse(evaluar(session, evento(sessionId, "Paso1_Palmas", 1_400, 2)));

            SessionManager.DetectionResult invalid = manager.procesarDeteccionHttp(
                evento(sessionId, "CLASE_DESCONOCIDA", 1_600, 3), manager.getOwnerToken(sessionId));

            assertEquals(SessionManager.DetectionOutcome.INVALID, invalid.outcome());
            assertEquals("MANOS_PRESENTES", session.getEstadoActualResponse().getEstadoIntencion());
            assertEquals(0L, session.getEstadoActualResponse().getTiempoConfirmacionIntencionMs());
            assertTrue(session.getEstadoActualResponse().getMotivoIntencion()
                .startsWith("Observación inválida"));

            assertFalse(evaluar(session, evento(sessionId, "Paso1_Palmas", 1_800, 3)),
                "el frame inválido reserva la secuencia y el mismo frame no puede repararse/repetirse");
            assertFalse(evaluar(session, evento(sessionId, "Paso1_Palmas", 2_200, 4)),
                "los votos empiezan de nuevo después de la observación inválida");
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void malformedObservationCannotAccreditDwellTimeAcrossItsGap() {
        SessionManager manager = new SessionManager(new Receiver());
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = manager.getSesion(sessionId);
            for (int index = 1; index <= 4; index++) {
                assertEquals(index >= 3,
                    evaluar(session, evento(sessionId, "Paso1_Palmas", 1_000L + index * 400L, index)));
            }
            assertEquals(1_200L, session.getTiempoTotalActivoMs());
            assertTrue(evaluar(session, evento(sessionId, "Paso1_Palmas", 3_000, 5)));
            assertEquals(1_600L, session.getTiempoTotalActivoMs());

            SessionManager.DetectionResult invalid = manager.procesarDeteccionHttp(
                evento(sessionId, "CLASE_DESCONOCIDA", 3_200, 6), manager.getOwnerToken(sessionId));
            assertEquals(SessionManager.DetectionOutcome.INVALID, invalid.outcome());

            assertTrue(evaluar(session, evento(sessionId, "Paso1_Palmas", 4_400, 7)));
            assertEquals(1_600L, session.getTiempoTotalActivoMs(),
                "el hueco desde la última observación válida no se acredita tras un rechazo");
            assertTrue(evaluar(session, evento(sessionId, "Paso1_Palmas", 4_800, 8)));
            assertEquals(2_000L, session.getTiempoTotalActivoMs());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void malformedObservationAlsoRestartsAnActiveExperimentalOmsAttempt() {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "omsInputEnabled", true);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = manager.getSesion(sessionId);
            session.procesarAccionOms(OmsAction.MOJAR_MANOS, null, 1_000L, 0.95f);
            session.procesarAccionOms(OmsAction.MOJAR_MANOS, null, 1_300L, 0.95f);
            assertEquals(300L, session.getTiempoTotalActivoMs());

            DetectionEvent invalid = new DetectionEvent(sessionId,
                OmsAction.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());
            invalid.setEvidenciaMovimiento(new MovementEvidence(null, 2, 0.2, true, 100L));
            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                invalid, manager.getOwnerToken(sessionId));

            assertEquals(SessionManager.DetectionOutcome.INVALID, result.outcome());
            assertEquals(0L, session.getTiempoTotalActivoMs());
            assertEquals(HandwashingSessionState.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(1, session.getIntentosReiniciados());
            assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
                session.getInfraccionActual().getTipo());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void malformedOmsObservationQueuesArchivedAttemptForPersistence() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = startOmsAttempt(manager, sessionId);
            DetectionEvent invalid = new DetectionEvent(sessionId,
                OmsAction.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());
            invalid.setEvidenciaMovimiento(new MovementEvidence(null, 2, 0.2, true, 100L));

            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                invalid, manager.getOwnerToken(sessionId));
            assertEquals(SessionManager.DetectionOutcome.INVALID, result.outcome());
            assertEquals(1, session.getIntentosReiniciados());

            manager.persistirIntentosFallidosPendientes();

            assertEquals(1, store.persisted.size(),
                "the asynchronous queue must persist an OMS attempt archived by rejection");
            assertEquals("OBSERVACION_NO_ACREDITABLE", store.persisted.get(0).motivoReinicio());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void inactivityExpiryQueuesArchivedAttemptForPersistence() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            ReflectionTestUtils.setField(manager, "timeoutMinutes", 1L);
            HandwashingSession session = startOmsAttempt(manager, sessionId);
            Object omsSession = ReflectionTestUtils.getField(session, "sesionOms");
            ReflectionTestUtils.setField(omsSession, "lastActivityMs",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2));
            ReflectionTestUtils.setField(omsSession, "lastActivityMonotonicNanos",
                System.nanoTime() - TimeUnit.MINUTES.toNanos(2));

            manager.expirarSesionesInactivas();
            assertEquals(HandwashingSessionState.EXPIRADA, session.getEstadoSesion());
            assertEquals(1, session.getIntentosReiniciados());

            manager.persistirIntentosFallidosPendientes();

            assertEquals(1, store.persisted.size(),
                "an incomplete attempt closed by inactivity must reach the bounded failure cache");
            assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD",
                store.persisted.get(0).motivoReinicio());
            assertEquals(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
                store.persisted.get(0).infracciones().get(0).getTipo());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void retentionSweepProtectsEverySessionStillRegisteredInMemory() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            manager.limpiarSesionesTerminadas();

            assertEquals(Set.of(sessionId), store.retainedSessions,
                "un resumen antiguo puede pertenecer a una sesión larga aún activa");
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void omsObservationWithoutRecentSpatialEvidenceCannotContinueAnActiveAttempt() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = startOmsAttempt(manager, sessionId);
            DetectionEvent missingEvidence = new DetectionEvent(sessionId,
                OmsAction.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());

            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                missingEvidence, manager.getOwnerToken(sessionId));

            assertEquals(SessionManager.DetectionOutcome.INVALID, result.outcome());
            assertTrue(result.detail().contains("pose bilateral"));
            assertEquals(0L, session.getTiempoTotalActivoMs());
            assertEquals(HandwashingSessionState.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(1, session.getIntentosReiniciados());

            manager.persistirIntentosFallidosPendientes();
            assertEquals(1, store.persisted.size());
            assertEquals("OBSERVACION_NO_ACREDITABLE", store.persisted.get(0).motivoReinicio());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void confidenceFilteredOmsObservationQueuesArchivedAttemptForPersistence() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        try {
            HandwashingSession session = startOmsAttempt(manager, sessionId);
            DetectionEvent filtered = new DetectionEvent(sessionId,
                OmsAction.MOJAR_MANOS.getClaseModelo(), 0.2f, Instant.now().toString());
            filtered.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 100L));

            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                filtered, manager.getOwnerToken(sessionId));
            assertEquals(SessionManager.DetectionOutcome.FILTERED, result.outcome());
            assertEquals(1, session.getIntentosReiniciados());

            manager.persistirIntentosFallidosPendientes();

            assertEquals(1, store.persisted.size(),
                "a filtered OMS observation can fail-close and must queue its archived attempt");
            assertEquals("OBSERVACION_NO_ACREDITABLE", store.persisted.get(0).motivoReinicio());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    private static SessionManager managerWith(FailedAttemptStore store) {
        SessionManager manager = new SessionManager(
            new Receiver(), new ValidationRuleStrategyFactory(), store);
        ReflectionTestUtils.setField(manager, "omsInputEnabled", true);
        return manager;
    }

    private static HandwashingSession startOmsAttempt(SessionManager manager, String sessionId) {
        HandwashingSession session = manager.getSesion(sessionId);
        session.procesarAccionOms(OmsAction.MOJAR_MANOS, null, 1_000L, 0.95f);
        session.procesarAccionOms(OmsAction.MOJAR_MANOS, null, 1_300L, 0.95f);
        assertEquals(300L, session.getTiempoTotalActivoMs());
        return session;
    }

    private static final class RecordingFailedAttemptStore implements FailedAttemptStore {
        private final List<HandwashingAttemptSummary> persisted = new ArrayList<>();
        private Set<String> retainedSessions = Set.of();

        @Override
        public void insertIfAbsent(String sessionId, HandwashingAttemptSummary attempt) {
            if (persisted.stream().noneMatch(existing -> existing.numero() == attempt.numero())) {
                persisted.add(attempt);
            }
        }

        @Override public List<HandwashingAttemptSummary> findBySession(String sessionId) {
            return List.copyOf(persisted);
        }
        @Override public int deleteBySession(String sessionId) {
            int count = persisted.size();
            persisted.clear();
            return count;
        }
        @Override
        public int deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds) {
            retainedSessions = Set.copyOf(retainedSessionIds);
            return 0;
        }
    }

    private static boolean evaluar(HandwashingSession session, DetectionEvent event) {
        boolean accepted = session.evaluarIntencion(event, INTENTION);
        if (accepted) {
            session.procesarDeteccion(event.getPasoLavadoResuelto(),
                event.getServerReceivedAtMonotonicMs(), event.getConfianza());
        }
        return accepted;
    }

    private static DetectionEvent evento(String sessionId, String label, long time, long sequence) {
        DetectionEvent event = new DetectionEvent(sessionId, label, 0.95f,
            Instant.now().toString());
        event.setServerReceivedAtMonotonicMs(time);
        event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, 0.2, true, 100L));
        return event;
    }
}
