package com.handwash.service;

import com.handwash.api.v1.dto.ProducerBuildIdentityRequest;
import com.handwash.model.EncounterAuditRecord;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.SessionAuditEvent;
import com.handwash.repository.SessionAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SessionAuditService {
    private static final String UNKNOWN = "UNVERIFIED";
    private final SessionAuditRepository repository;
    private final boolean disabled;
    @Value("${handwash.audit.model-version:UNVERIFIED}")
    private String modelVersion = UNKNOWN;
    @Value("${handwash.audit.model-sha256:UNVERIFIED}")
    private String modelSha256 = UNKNOWN;
    @Value("${handwash.audit.dataset-version:UNVERIFIED}")
    private String datasetVersion = UNKNOWN;

    public record AuditTransition(String eventType, String stepCode, String decisionCode,
                                  String reasonCode, Long frameSequence, Double confidence) {}

    @Autowired
    public SessionAuditService(SessionAuditRepository repository) {
        this(repository, false);
    }

    private SessionAuditService(SessionAuditRepository repository, boolean disabled) {
        this.repository = repository;
        this.disabled = disabled;
    }

    public static SessionAuditService noop() {
        return new SessionAuditService(null, true);
    }

    @Transactional
    public void createEncounter(WashSession session) {
        if (disabled) return;
        long now = System.currentTimeMillis();
        repository.createEncounter(new EncounterAuditRecord(
            session.getSessionId(), session.getProtocolo().value(), "ACTIVE", now, null,
            bounded(modelVersion, 128), bounded(modelSha256, 64), bounded(datasetVersion, 128), 0,
            null, null, session.getEstrategia().getVersionPerfilProtocolo()));
        append(session.getSessionId(), "SESSION_CREATED", "SYSTEM", null, "CREATED", null,
            1, null, null, now);
    }

    @Transactional
    public void createEncounterForStation(WashSession session, String stationId) {
        if (disabled) return;
        long now = System.currentTimeMillis();
        repository.createEncounter(new EncounterAuditRecord(
            session.getSessionId(), session.getProtocolo().value(), "ACTIVE", now, null,
            bounded(modelVersion, 128), bounded(modelSha256, 64), bounded(datasetVersion, 128), 0,
            null, null, session.getEstrategia().getVersionPerfilProtocolo()));
        append(session.getSessionId(), "SESSION_CREATED", "STATION", null, "CREATED", null,
            1, null, null, now);
        append(session.getSessionId(), "STATION_SESSION_STARTED", bounded(stationId, 128), null,
            "STARTED", "REGISTERED_STATION", 1, null, null, now);
    }

    public void validateProducerBuild(ProducerBuildIdentityRequest build) {
        if (build == null
            || !boundedEquals(build.modelVersion(), modelVersion, 128)
            || !boundedEquals(build.datasetVersion(), datasetVersion, 128)
            || build.modelSha256() == null
            || !build.modelSha256().matches("(?i)[a-f0-9]{64}")
            || !build.modelSha256().equalsIgnoreCase(modelSha256)) {
            throw new IllegalArgumentException("La versión declarada del productor no coincide con el release.");
        }
    }

    @Transactional
    public void recordProducerEpoch(String sessionId, String producerClientId,
                                    String producerEpoch, boolean rotated) {
        if (disabled) return;
        if (repository.registerProducer(sessionId, bounded(producerClientId, 128),
                bounded(producerEpoch, 64)) != 1) {
            throw new IllegalStateException("El encuentro no está activo para registrar productor.");
        }
        long now = System.currentTimeMillis();
        append(sessionId, rotated ? "PRODUCER_EPOCH_ROTATED" : "PRODUCER_EPOCH_REGISTERED",
            producerClientId, null, rotated ? "ROTATED" : "REGISTERED",
            "BUILD_IDENTITY_MATCHED", null, null, null, now);
    }

    /** Fails an old in-memory encounter closed; it never pretends to resume lost temporal evidence. */
    @Transactional
    public int interruptNonTerminalEncountersAfterRestart() {
        if (disabled) return 0;
        int interrupted = 0;
        for (String sessionId : repository.findNonTerminalEncounterIds()) {
            long now = System.currentTimeMillis();
            if (repository.interruptEncounterAfterRuntimeRestart(sessionId, now) == 1) {
                append(sessionId, "SESSION_INTERRUPTED", "SYSTEM", null, "INTERRUPTED",
                    "BACKEND_RESTART", null, null, null, now);
                interrupted++;
            }
        }
        return interrupted;
    }

    @Transactional
    public void recordStateChanges(WashSession session, List<AuditTransition> transitions) {
        if (disabled) return;
        if (transitions == null || transitions.isEmpty()) return;
        long now = System.currentTimeMillis();
        SessionStatus state = session.getEstadoSesion();
        Long endedAt = state == SessionStatus.COMPLETADA || state == SessionStatus.EXPIRADA
            ? now : null;
        String persistedStatus = switch (state) {
            case ESPERANDO_INICIO -> "ACTIVE";
            case EN_PROGRESO -> "IN_PROGRESS";
            case COMPLETADA -> "COMPLETED";
            case EXPIRADA -> "INTERRUPTED";
        };
        requireEncounterUpdated(repository.updateEncounter(session.getSessionId(), persistedStatus,
            endedAt, session.getIntentosReiniciados()));
        for (AuditTransition transition : transitions) {
            append(session.getSessionId(), bounded(transition.eventType(), 48), "SYSTEM",
                transition.stepCode() == null ? null : bounded(transition.stepCode(), 64),
                bounded(transition.decisionCode(), 48), transition.reasonCode() == null ? null
                    : bounded(transition.reasonCode(), 80),
                Math.max(1, session.getNumeroIntentoActual()), transition.frameSequence(),
                transition.confidence(), now);
        }
    }

    @Transactional
    public void recordSessionDeleted(String sessionId, int attemptNumber) {
        if (disabled) return;
        long now = System.currentTimeMillis();
        requireEncounterUpdated(repository.updateEncounter(sessionId, "DELETED", now,
            Math.max(0, attemptNumber - 1)));
        append(sessionId, "SESSION_DELETED", "OWNER", null, "DELETED", null,
            Math.max(1, attemptNumber), null, null, now);
    }

    @Transactional
    public void recordSessionDeleteFailed(String sessionId, int attemptNumber) {
        if (disabled) return;
        append(sessionId, "SESSION_DELETE_FAILED", "OWNER", null, "NOT_DELETED", null,
            Math.max(1, attemptNumber), null, null, System.currentTimeMillis());
    }

    @Transactional
    public void recordSessionDeleteRequested(String sessionId, int attemptNumber) {
        if (disabled) return;
        append(sessionId, "SESSION_DELETE_REQUESTED", "OWNER", null, "REQUESTED", null,
            Math.max(1, attemptNumber), null, null, System.currentTimeMillis());
    }

    /** Rehearsal mode: counts only (step, confirmed/corrected, corrected-to); never any image or landmark. */
    @Transactional
    public void recordOperatorStepFeedback(String sessionId, int attemptNumber, String stepCode,
                                           boolean corrected, String correctedToStepCode) {
        if (disabled) return;
        append(sessionId, "OPERATOR_STEP_FEEDBACK", "OWNER", stepCode,
            corrected ? "CORRECTED" : "CONFIRMED",
            corrected ? "CORRECTED_TO_" + correctedToStepCode : null,
            Math.max(1, attemptNumber), null, null, System.currentTimeMillis());
    }

    @Transactional
    public void recordAdminAccess(String actorSubject, String eventType, String sessionId) {
        if (disabled) return;
        if (!List.of("ADMIN_ENCOUNTERS_LISTED", "ADMIN_EVENTS_READ", "ADMIN_ACCESS_LOG_READ",
            "ADMIN_FAILED_ATTEMPTS_READ", "ADMIN_STATIONS_LISTED", "ADMIN_OPERATORS_LISTED",
            "ADMIN_OPERATOR_CODES_LISTED", "ADMIN_PROTOCOL_PROFILE_READ", "ADMIN_WASH_REPORTS_READ",
            "ADMIN_EVIDENCE_SETTINGS_READ", "ADMIN_EVIDENCE_PHOTO_VIEWED", "ADMIN_AUDIT_CHAIN_VERIFIED",
            "ADMIN_AUDIT_SEALED", "ADMIN_CHANGE_CONTROL_READ", "ADMIN_WASH_TRENDS_READ",
            "ADMIN_WASH_REPORTS_EXPORTED")
            .contains(eventType)) {
            throw new IllegalArgumentException("El evento de acceso ADMIN no está permitido.");
        }
        append(sessionId, eventType, bounded(actorSubject, 128), null, "READ", null,
            null, null, null, System.currentTimeMillis());
    }

    @Transactional
    public void recordStationEvent(String sessionId, String actorSubject, String eventType,
                                   String decisionCode, String reasonCode) {
        if (disabled) return;
        if (!List.of("STATION_ENROLLMENT_CODE_ISSUED", "STATION_ENROLLED", "STATION_REVOKED",
            "STATION_PRODUCER_BOUND", "STATION_PROVISIONED", "STATION_ACTIVATED",
            "STATION_ACTIVATION_REISSUED", "OPERATOR_ACCOUNT_CREATED", "OPERATOR_ACCOUNT_DISABLE_FAILED",
            "ADMIN_ACCOUNT_BOOTSTRAPPED", "ADMIN_LOGIN", "ADMIN_LOGIN_LOCKED", "ADMIN_PASSWORD_CHANGED", "STATION_LINKED_BY_KEY", "API_KEY_ISSUED", "API_KEY_REVOKED", "API_KEY_LOGIN", "OPERATOR_ACCESS_CODE_ISSUED",
            "OPERATOR_ACCESS_CODE_CLAIMED", "OPERATOR_ACCESS_CODE_REVOKED",
            "OPERATOR_GRANT_REVOKED", "OPERATOR_SESSION_STARTED", "OPERATOR_SESSION_STOPPED",
            "OPERATOR_SESSION_TICKET_ISSUED", "PROTOCOL_PROFILE_VERSION_CREATED",
            "OPERATOR_SHIFT_STARTED", "OPERATOR_SHIFT_STOPPED", "STATION_SHIFT_ENDED",
            "ADMIN_EVIDENCE_ENABLED", "ADMIN_EVIDENCE_DISABLED", "EVIDENCE_PHOTO_STORED", "EVIDENCE_PHOTO_EXPIRED",
            "PROTOCOL_PROFILE_VERSION_PENDING", "PROTOCOL_PROFILE_APPROVED", "PROTOCOL_PROFILE_REJECTED",
            "PROTOCOL_PROFILE_REVERTED", "CHANGE_CONTROL_SETTINGS_CHANGED", "MODEL_RELEASE_REGISTERED",
            "VALIDATION_REPORT_REGISTERED")
            .contains(eventType)) {
            throw new IllegalArgumentException("El evento de estación no está permitido");
        }
        append(sessionId, eventType, bounded(actorSubject, 128), null,
            bounded(decisionCode, 48), reasonCode == null ? null : bounded(reasonCode, 80),
            null, null, null, System.currentTimeMillis());
    }

    public List<EncounterAuditRecord> recentEncounters(int limit) {
        return repository.findRecentEncounters(limit);
    }

    public List<SessionAuditEvent> events(String sessionId, Long beforeEventId, int limit) {
        return repository.findEvents(sessionId, beforeEventId, limit);
    }

    public List<SessionAuditEvent> adminAccessEvents(int limit) {
        return repository.findAdminAccessEvents(limit);
    }

    public boolean encounterExists(String sessionId) {
        return repository.encounterExists(sessionId);
    }

    private static void requireEncounterUpdated(int rows) {
        if (rows != 1) throw new IllegalStateException("El encuentro auditable no existe.");
    }

    private void append(String sessionId, String eventType, String actorSubject, String stepCode,
                        String decisionCode, String reasonCode, Integer attemptNumber,
                        Long frameSequence, Double confidence, long occurredAt) {
        repository.appendEvent(new SessionAuditEvent(0L, sessionId, bounded(eventType, 48),
            actorSubject == null ? null : bounded(actorSubject, 128),
            stepCode == null ? null : bounded(stepCode, 64), bounded(decisionCode, 48),
            reasonCode == null ? null : bounded(reasonCode, 80), attemptNumber,
            frameSequence, confidence, occurredAt));
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("El dato de auditoría es inválido o excede su límite");
        }
        return value;
    }

    private static boolean boundedEquals(String reported, String expected, int maxLength) {
        return reported != null && !reported.isBlank() && reported.length() <= maxLength
            && reported.equals(expected);
    }
}
