package com.handwash.agent;

import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.DetectionEvent;
import com.handwash.model.ProtocolType;
import com.handwash.observer.DetectionPipelineException;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionAuditObserverTest {
    @Test
    void processingFailureIsNotAuditedAsInactivity() {
        DetectionReceiver broken = new DetectionReceiver() {
            @Override public DetectionEvent recibirValidada(DetectionEvent event) {
                throw new DetectionPipelineException("stage failed", new IllegalStateException());
            }
        };
        SessionManager sessions = new SessionManager(broken);
        String id = sessions.crearSesion(ProtocolType.DOMESTICO);
        SessionAuditService audit = mock(SessionAuditService.class);
        var observer = new SessionAuditObserver(sessions, audit);
        observer.registerSession(sessions.getSesion(id));
        broken.addObserver(observer);
        try {
            assertThrows(DetectionPipelineException.class, () -> sessions.procesarDeteccionHttp(
                new DetectionEvent(id, "PASO_1_PALMAS", 0.95f, java.time.Instant.now().toString()),
                sessions.getOwnerToken(id)));
            verify(audit).recordStateChanges(any(), argThat(transitions -> transitions.stream().anyMatch(
                event -> "SESSION_INTERRUPTED".equals(event.eventType())
                    && "PIPELINE_FAILURE".equals(event.reasonCode()))));
        } finally { sessions.eliminarSesion(id); }
    }

    @Test
    void recordsOnlyARealSessionStartAndDoesNotDuplicateUnchangedState() {
        String sessionId = UUID.randomUUID().toString();
        SessionManager sessions = mock(SessionManager.class);
        SessionAuditService audit = mock(SessionAuditService.class);
        WashSession session = mock(WashSession.class);
        when(session.getSessionId()).thenReturn(sessionId);
        when(session.getEstadoSesion()).thenReturn(
            SessionStatus.ESPERANDO_INICIO, SessionStatus.EN_PROGRESO, SessionStatus.EN_PROGRESO);
        when(session.getPasosCompletados()).thenReturn(0, 0, 0);
        when(session.getIntentosReiniciados()).thenReturn(0, 0, 0);
        when(sessions.getSesion(sessionId)).thenReturn(session);
        when(sessions.getSesionesSnapshot()).thenReturn(Map.of(sessionId, session));
        SessionAuditObserver observer = new SessionAuditObserver(sessions, audit);

        observer.registerSession(session);
        observer.onStateChanged(sessionId);
        observer.onStateChanged(sessionId);

        verify(audit).recordStateChanges(any(), argThat(transitions ->
            transitions.size() == 1 && transitions.get(0).eventType().equals("WASH_STARTED")));
        verify(audit, times(1)).recordStateChanges(any(), any());
    }
}
