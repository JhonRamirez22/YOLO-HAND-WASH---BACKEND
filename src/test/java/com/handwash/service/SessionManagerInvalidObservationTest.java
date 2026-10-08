package com.handwash.service;

import com.handwash.agent.Receptor;
import com.handwash.intention.CadenaIntencionLavado;
import com.handwash.model.AccionOms;
import com.handwash.model.DeteccionEvento;
import com.handwash.model.EvidenciaMovimiento;
import com.handwash.model.EstadoSesion;
import com.handwash.model.IntentoLavadoResumen;
import com.handwash.model.PasoLavado;
import com.handwash.model.SesionLavado;
import com.handwash.model.TipoInfraccion;
import com.handwash.model.TipoProtocolo;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.strategy.ReglaValidacionStrategyFactory;
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
    private static final CadenaIntencionLavado INTENTION =
        new CadenaIntencionLavado(650, 650, 3, 1500, 0.75);

    @Test
    void malformedObservationBreaksStartVotesAndConsumesItsFreshEvidenceSequence() {
        SessionManager manager = new SessionManager(new Receptor());
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = manager.getSesion(sessionId);
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
        SessionManager manager = new SessionManager(new Receptor());
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = manager.getSesion(sessionId);
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
        SessionManager manager = new SessionManager(new Receptor());
        ReflectionTestUtils.setField(manager, "omsInputEnabled", true);
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = manager.getSesion(sessionId);
            session.procesarAccionOms(AccionOms.MOJAR_MANOS, null, 1_000L, 0.95f);
            session.procesarAccionOms(AccionOms.MOJAR_MANOS, null, 1_300L, 0.95f);
            assertEquals(300L, session.getTiempoTotalActivoMs());

            DeteccionEvento invalid = new DeteccionEvento(sessionId,
                AccionOms.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());
            invalid.setEvidenciaMovimiento(new EvidenciaMovimiento(null, 2, 0.2, true, 100L));
            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                invalid, manager.getOwnerToken(sessionId));

            assertEquals(SessionManager.DetectionOutcome.INVALID, result.outcome());
            assertEquals(0L, session.getTiempoTotalActivoMs());
            assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(1, session.getIntentosReiniciados());
            assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
                session.getInfraccionActual().getTipo());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void malformedOmsObservationQueuesArchivedAttemptForPersistence() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = startOmsAttempt(manager, sessionId);
            DeteccionEvento invalid = new DeteccionEvento(sessionId,
                AccionOms.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());
            invalid.setEvidenciaMovimiento(new EvidenciaMovimiento(null, 2, 0.2, true, 100L));

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
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            ReflectionTestUtils.setField(manager, "timeoutMinutes", 1L);
            SesionLavado session = startOmsAttempt(manager, sessionId);
            Object omsSession = ReflectionTestUtils.getField(session, "sesionOms");
            ReflectionTestUtils.setField(omsSession, "lastActivityMs",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2));
            ReflectionTestUtils.setField(omsSession, "lastActivityMonotonicNanos",
                System.nanoTime() - TimeUnit.MINUTES.toNanos(2));

            manager.expirarSesionesInactivas();
            assertEquals(EstadoSesion.EXPIRADA, session.getEstadoSesion());
            assertEquals(1, session.getIntentosReiniciados());

            manager.persistirIntentosFallidosPendientes();

            assertEquals(1, store.persisted.size(),
                "an incomplete attempt closed by inactivity must reach the bounded failure cache");
            assertEquals("SESION_EXPIRADA_POR_INACTIVIDAD",
                store.persisted.get(0).motivoReinicio());
            assertEquals(TipoInfraccion.EVIDENCIA_VISUAL_INTERRUPTA,
                store.persisted.get(0).infracciones().get(0).getTipo());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void retentionSweepProtectsEverySessionStillRegisteredInMemory() {
        RecordingFailedAttemptStore store = new RecordingFailedAttemptStore();
        SessionManager manager = managerWith(store);
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
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
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = startOmsAttempt(manager, sessionId);
            DeteccionEvento missingEvidence = new DeteccionEvento(sessionId,
                AccionOms.MOJAR_MANOS.getClaseModelo(), 0.95f, Instant.now().toString());

            SessionManager.DetectionResult result = manager.procesarDeteccionHttp(
                missingEvidence, manager.getOwnerToken(sessionId));

            assertEquals(SessionManager.DetectionOutcome.INVALID, result.outcome());
            assertTrue(result.detail().contains("pose bilateral"));
            assertEquals(0L, session.getTiempoTotalActivoMs());
            assertEquals(EstadoSesion.ESPERANDO_INICIO, session.getEstadoSesion());
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
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        try {
            SesionLavado session = startOmsAttempt(manager, sessionId);
            DeteccionEvento filtered = new DeteccionEvento(sessionId,
                AccionOms.MOJAR_MANOS.getClaseModelo(), 0.2f, Instant.now().toString());
            filtered.setEvidenciaMovimiento(new EvidenciaMovimiento(1L, 2, 0.2, true, 100L));

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
            new Receptor(), new ReglaValidacionStrategyFactory(), store);
        ReflectionTestUtils.setField(manager, "omsInputEnabled", true);
        return manager;
    }

    private static SesionLavado startOmsAttempt(SessionManager manager, String sessionId) {
        SesionLavado session = manager.getSesion(sessionId);
        session.procesarAccionOms(AccionOms.MOJAR_MANOS, null, 1_000L, 0.95f);
        session.procesarAccionOms(AccionOms.MOJAR_MANOS, null, 1_300L, 0.95f);
        assertEquals(300L, session.getTiempoTotalActivoMs());
        return session;
    }

    private static final class RecordingFailedAttemptStore implements FailedAttemptStore {
        private final List<IntentoLavadoResumen> persisted = new ArrayList<>();
        private Set<String> retainedSessions = Set.of();

        @Override
        public void insertIfAbsent(String sessionId, IntentoLavadoResumen attempt) {
            if (persisted.stream().noneMatch(existing -> existing.numero() == attempt.numero())) {
                persisted.add(attempt);
            }
        }

        @Override public List<IntentoLavadoResumen> findBySession(String sessionId) {
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

    private static boolean evaluar(SesionLavado session, DeteccionEvento event) {
        boolean accepted = session.evaluarIntencion(event, INTENTION);
        if (accepted) {
            session.procesarDeteccion(event.getPasoLavadoResuelto(),
                event.getServerReceivedAtMonotonicMs(), event.getConfianza());
        }
        return accepted;
    }

    private static DeteccionEvento evento(String sessionId, String label, long time, long sequence) {
        DeteccionEvento event = new DeteccionEvento(sessionId, label, 0.95f,
            Instant.now().toString());
        event.setServerReceivedAtMonotonicMs(time);
        event.setEvidenciaMovimiento(new EvidenciaMovimiento(sequence, 2, 0.2, true, 100L));
        return event;
    }
}
