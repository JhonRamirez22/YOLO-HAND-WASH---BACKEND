package com.handwash.agent;

import com.handwash.model.WhoAction;
import com.handwash.model.DetectionEvent;
import com.handwash.model.SessionStatus;
import com.handwash.model.Infraction;
import com.handwash.model.WashStep;
import com.handwash.model.WashSession;
import com.handwash.observer.DetectionObserver;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Persists only session-level decisions and confirmed transitions, never per-frame imagery. */
@Component
public final class SessionAuditObserver implements DetectionObserver {
    private final SessionManager sessions;
    private final SessionAuditService audit;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public SessionAuditObserver(SessionManager sessions, SessionAuditService audit) {
        this.sessions = sessions;
        this.audit = audit;
    }

    public void registerSession(WashSession session) {
        if (session != null) snapshots.put(session.getSessionId(), snapshot(session));
    }

    public void forgetSession(String sessionId) {
        if (sessionId != null) snapshots.remove(sessionId);
    }

    @Override
    public void onDeteccion(DetectionEvent event) {
        if (event == null) return;
        observe(event.getSessionId(), event);
    }

    @Override
    public void onStateChanged(String sessionId) {
        observe(sessionId, null);
    }

    private void observe(String sessionId, DetectionEvent event) {
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return;
        synchronized (session) {
            if (sessions.getSesion(sessionId) != session) return;
            Snapshot current = snapshot(session);
            Snapshot previous = snapshots.getOrDefault(sessionId,
                new Snapshot(SessionStatus.ESPERANDO_INICIO, 0, 0));
            if (previous.equals(current)) return;
            List<SessionAuditService.AuditTransition> transitions = new ArrayList<>(4);

            if (previous.status() != SessionStatus.EN_PROGRESO
                && current.status() == SessionStatus.EN_PROGRESO) {
                transitions.add(transition("WASH_STARTED", null, "STARTED", null, event));
            }
            if (current.stepsCompleted() > previous.stepsCompleted()) {
                String step = canonicalStep(event);
                transitions.add(transition("STEP_CONFIRMED", step, "CONFIRMED", null, event));
            }
            if (current.restarts() > previous.restarts()) {
                Infraction lastError = session.getEstadoActualResponse().getUltimoErrorReinicio();
                String reason = lastError == null || lastError.getTipo() == null
                    ? "UNSPECIFIED" : lastError.getTipo().name();
                transitions.add(transition("ATTEMPT_RESTARTED", canonicalStep(event),
                    "RESTARTED", reason, event));
            }
            if (previous.status() != SessionStatus.COMPLETADA
                && current.status() == SessionStatus.COMPLETADA) {
                transitions.add(transition("WASH_COMPLETED", null, "COMPLETED", null, event));
            } else if (previous.status() != SessionStatus.EXPIRADA
                && current.status() == SessionStatus.EXPIRADA) {
                transitions.add(transition("SESSION_INTERRUPTED", null, "INTERRUPTED",
                    session.getMotivoExpiracion() == null ? "UNSPECIFIED"
                        : session.getMotivoExpiracion().name(), event));
            }
            audit.recordStateChanges(session, transitions);
            snapshots.put(sessionId, current);
        }
    }

    private static SessionAuditService.AuditTransition transition(String type, String step,
        String decision, String reason, DetectionEvent event) {
        Double confidence = event == null || event.getConfianza() == null
            ? null : (double) event.getConfianza();
        return new SessionAuditService.AuditTransition(type, step, decision, reason,
            event == null ? null : event.getFrameSequence(), confidence);
    }

    private static String canonicalStep(DetectionEvent event) {
        if (event == null) return null;
        WashStep step = event.getPasoLavadoResuelto();
        if (step != null && step != WashStep.FONDO) return step.getClaseModelo();
        WhoAction action = event.getAccionOmsResuelta();
        return action == null ? null : action.getClaseModelo();
    }

    private static Snapshot snapshot(WashSession session) {
        return new Snapshot(session.getEstadoSesion(), session.getPasosCompletados(),
            session.getIntentosReiniciados());
    }

    @Scheduled(fixedDelayString = "${handwash.session.cleanup-check-ms:60000}")
    public void removeSnapshotsForRetiredSessions() {
        var activeIds = sessions.getSesionesSnapshot().keySet();
        snapshots.keySet().removeIf(id -> !activeIds.contains(id));
    }

    private record Snapshot(SessionStatus status, int stepsCompleted, int restarts) {}
}
