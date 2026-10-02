package com.handwash.controller;

import com.handwash.agent.Notifier;
import com.handwash.agent.SessionAuditObserver;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.OperatorCodeClaimRequest;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.StepFeedbackRequest;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.security.WebSocketTicketService;
import com.handwash.service.CloudStationSessionStarter;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.service.StationShiftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@Profile("cloud")
@RequestMapping("/api/v1/operator")
public class OperatorAccessController {
    private final StationOperatorAccessService access;
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final SessionAuditService audit;
    private final SessionAuditObserver auditObserver;
    private final CloudStationSessionStarter sessionStarter;
    private final RequestRateLimiter rateLimiter;
    private final Notifier notifier;
    private final WebSocketTicketService websocketTickets;
    private final JwtDecoder idTokenDecoder;
    private final String userClientId;
    private final StationShiftService shifts;
    private final com.handwash.security.WebRtcTicketService webrtcTickets;
    private final com.handwash.webrtc.IceServerProvider iceServers;

    public OperatorAccessController(StationOperatorAccessService access,
        StationCredentialService stations, SessionManager sessions, SessionAuditService audit,
        SessionAuditObserver auditObserver, CloudStationSessionStarter sessionStarter,
        RequestRateLimiter rateLimiter, Notifier notifier, WebSocketTicketService websocketTickets,
        @Qualifier("cognitoIdJwtDecoder") JwtDecoder idTokenDecoder,
        @Value("${handwash.security.cognito-client-id}") String userClientId,
        StationShiftService shifts, com.handwash.security.WebRtcTicketService webrtcTickets,
        com.handwash.webrtc.IceServerProvider iceServers) {
        this.access = access;
        this.stations = stations;
        this.sessions = sessions;
        this.audit = audit;
        this.auditObserver = auditObserver;
        this.sessionStarter = sessionStarter;
        this.rateLimiter = rateLimiter;
        this.notifier = notifier;
        this.websocketTickets = websocketTickets;
        this.idTokenDecoder = idTokenDecoder;
        this.userClientId = userClientId;
        this.shifts = shifts;
        this.webrtcTickets = webrtcTickets;
        this.iceServers = iceServers;
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim(@AuthenticationPrincipal Jwt operator,
        @RequestHeader(value = "X-Cognito-Id-Token", required = false) String idToken,
        @RequestBody(required = false) OperatorCodeClaimRequest request,
        HttpServletRequest httpRequest) {
        if (!isHuman(operator)) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("CUENTA_COGNITO_NO_AUTORIZADA"));
        if (!rateLimiter.allowPairing(com.handwash.security.ClientAddress.of(httpRequest))) return ResponseEntity.status(429)
            .body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        if (idToken == null || idToken.length() > 8_192) return ResponseEntity.status(401)
            .body(ApiErrorResponse.of("ID_TOKEN_NO_VALIDO"));
        final Jwt identity;
        try {
            identity = idTokenDecoder.decode(idToken);
        } catch (JwtException invalid) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ID_TOKEN_NO_VALIDO"));
        }
        if (!operator.getSubject().equals(identity.getSubject())
            || !Boolean.TRUE.equals(identity.getClaimAsBoolean("email_verified"))) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("CORREO_NO_VERIFICADO"));
        }
        String email = identity.getClaimAsString("email");
        StationOperatorAccessService.ClaimResult result = access.claim(
            request == null ? null : request.code(), operator.getSubject(), email);
        return switch (result.outcome()) {
            case CLAIMED -> ResponseEntity.ok(result.grant());
            case EMAIL_MISMATCH -> ResponseEntity.status(403)
                .body(ApiErrorResponse.of("CORREO_NO_COINCIDE"));
            case STATION_UNAVAILABLE -> ResponseEntity.status(409)
                .body(ApiErrorResponse.of("PUESTO_NO_DISPONIBLE"));
            case ALREADY_ASSIGNED -> ResponseEntity.status(409)
                .body(ApiErrorResponse.of("OPERADOR_YA_ASIGNADO"));
            case INVALID_CODE -> ResponseEntity.status(401)
                .body(ApiErrorResponse.of("CODIGO_OPERADOR_INVALIDO"));
        };
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt operator) {
        if (!isHuman(operator)) return ResponseEntity.status(403).build();
        try {
        StationOperatorAccessService.GrantView grant = access.activeGrantForUpdate(operator.getSubject());
            if (grant == null) return ResponseEntity.status(404)
                .body(ApiErrorResponse.of("OPERADOR_SIN_ASIGNACION"));
            StationRecord station = stations.activeStation(grant.stationId());
            SessionView activeSession = null;
            if (station != null && station.activeSessionId() != null) {
                WashSession session = sessions.getSesion(station.activeSessionId());
                if (isActive(session)) activeSession = new SessionView(session.getSessionId(),
                    session.getProtocolo().value());
            }
            return ResponseEntity.ok(new OperatorStatus(grant, activeSession));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("ASIGNACION_NO_DISPONIBLE"));
        }
    }

    @PostMapping("/resume")
    @Transactional
    public ResponseEntity<?> resume(@AuthenticationPrincipal Jwt operator) {
        StationRecord station = authorizedStation(operator);
        if (station == null) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("OPERADOR_SIN_ASIGNACION"));
        if (station.activeSessionId() == null) return ResponseEntity.noContent().build();
        WashSession session = sessions.getSesion(station.activeSessionId());
        if (!isActive(session)) return ResponseEntity.status(409)
            .body(ApiErrorResponse.of("SESION_NO_RECUPERABLE"));
        return ResponseEntity.ok(sessionStarter.responseFor(session));
    }

    @PostMapping("/session")
    @Transactional
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt operator,
        @RequestBody(required = false) SessionCreateRequest request,
        @RequestHeader(value = "X-Station-Request-Id", required = false) String requestId,
        HttpServletRequest httpRequest) {
        StationRecord station = authorizedStation(operator);
        if (station == null) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("OPERADOR_SIN_ASIGNACION"));
        if (!rateLimiter.allowSessionCreation(com.handwash.security.ClientAddress.of(httpRequest))) return ResponseEntity.status(429)
            .body(ApiErrorResponse.of("LIMITE_CREACION_SESIONES"));
        ResponseEntity<?> response = sessionStarter.start(station, request, requestId);
        if (response.getStatusCode().is2xxSuccessful()
            && response.getBody() instanceof com.handwash.api.v1.dto.SessionCreatedResponse created) {
            audit.recordStationEvent(created.sessionId(), operator.getSubject(),
                "OPERATOR_SESSION_STARTED", "STARTED", station.stationId());
        }
        return response;
    }

    @PostMapping("/sessions/{sessionId}/websocket-ticket")
    @Transactional
    public ResponseEntity<?> websocketTicket(@AuthenticationPrincipal Jwt operator,
                                             @PathVariable String sessionId) {
        if (!ownsSession(operator, sessionId)) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        String ownerToken = sessions.getOwnerToken(sessionId);
        WebSocketTicketService.IssueResult result = websocketTickets.issue(sessionId, ownerToken);
        if (result.outcome() != WebSocketTicketService.IssueOutcome.ISSUED) {
            return ResponseEntity.status(result.outcome() == WebSocketTicketService.IssueOutcome.CAPACITY
                ? 429 : 401).body(ApiErrorResponse.of("TICKET_WEBSOCKET_NO_DISPONIBLE"));
        }
        audit.recordStationEvent(sessionId, operator.getSubject(),
            "OPERATOR_SESSION_TICKET_ISSUED", "ISSUED", null);
        return ResponseEntity.ok(new WebSocketTicketResponse(result.issue().value(),
            Instant.ofEpochMilli(result.issue().expiresAtEpochMs()).toString()));
    }

    /**
     * WebRTC ticket for whichever device of the operator holds the camera (a browser on a Mac or a phone, or the
     * native iPhone helper): it needs the operator's authorization over the station's active session, not the
     * station's own credential.
     */
    @PostMapping("/sessions/{sessionId}/webrtc-ticket")
    @Transactional
    public ResponseEntity<?> webrtcTicket(@AuthenticationPrincipal Jwt operator,
                                          @PathVariable String sessionId) {
        if (!ownsSession(operator, sessionId)) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        var result = webrtcTickets.issue(sessionId, sessions.getOwnerToken(sessionId));
        return switch (result.outcome()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case UNAUTHORIZED -> ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            case CAPACITY -> ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_TICKETS_WEBRTC"));
            case ISSUED -> ResponseEntity.ok(new com.handwash.api.v1.dto.WebRtcTicketResponse(result.issued().value(),
                result.role().name(), Instant.ofEpochMilli(result.issued().expiresAtMs()).toString(),
                iceServers.servers(sessionId, result.role())));
        };
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Transactional
    public ResponseEntity<?> stop(@AuthenticationPrincipal Jwt operator,
                                  @PathVariable String sessionId) {
        if (!ownsSession(operator, sessionId)) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return ResponseEntity.notFound().build();
        // Stopping the session is an explicit decision: end the shift first so it is not restarted.
        StationRecord stoppedStation = authorizedStation(operator);
        if (stoppedStation != null) shifts.endOnManualSessionStop(stoppedStation.stationId(), operator.getSubject());
        try {
            audit.recordSessionDeleteRequested(sessionId, session.getNumeroIntentoActual());
        } catch (RuntimeException auditFailure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
        }
        if (!sessions.eliminarSesion(sessionId)) {
            try { audit.recordSessionDeleteFailed(sessionId, session.getNumeroIntentoActual()); }
            catch (RuntimeException ignored) { /* Request audit already records the uncertain outcome. */ }
            return ResponseEntity.status(503).body(ApiErrorResponse.of("ELIMINACION_NO_CONFIRMADA"));
        }
        notifier.eliminarSesion(sessionId);
        auditObserver.forgetSession(sessionId);
        try {
            audit.recordSessionDeleted(sessionId, session.getNumeroIntentoActual());
            StationOperatorAccessService.GrantView grant = access.activeGrant(operator.getSubject());
            audit.recordStationEvent(sessionId, operator.getSubject(), "OPERATOR_SESSION_STOPPED",
                "STOPPED", grant == null ? null : grant.stationId());
        } catch (RuntimeException auditFailure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("ELIMINACION_AUDITORIA_NO_CONFIRMADA"));
        }
        return ResponseEntity.noContent().build();
    }

    /** Rehearsal mode: the operator of the station's active session confirms or corrects a detected step. */
    @PostMapping("/sessions/{sessionId}/feedback")
    @Transactional
    public ResponseEntity<?> stepFeedback(@AuthenticationPrincipal Jwt operator,
                                          @PathVariable String sessionId,
                                          @RequestBody(required = false) StepFeedbackRequest request) {
        if (!ownsSession(operator, sessionId)) return ResponseEntity.status(403)
            .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return ResponseEntity.notFound().build();
        var feedback = request == null ? null : request.validate();
        if (feedback == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("RETROALIMENTACION_INVALIDA"));
        }
        try {
            audit.recordOperatorStepFeedback(sessionId, session.getNumeroIntentoActual(),
                feedback.step().getClaseModelo(), feedback.corrected(),
                feedback.correctStep() == null ? null : feedback.correctStep().getClaseModelo());
        } catch (RuntimeException auditFailure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
        }
        return ResponseEntity.noContent().build();
    }

    private StationRecord authorizedStation(Jwt operator) {
        if (!isHuman(operator)) return null;
        StationOperatorAccessService.GrantView grant = access.activeGrantForUpdate(operator.getSubject());
        return grant == null ? null : stations.activeStation(grant.stationId());
    }

    private boolean ownsSession(Jwt operator, String sessionId) {
        if (sessionId == null || !sessionId.matches("(?i)[0-9a-f-]{36}")) return false;
        StationRecord station = authorizedStation(operator);
        return station != null && sessionId.equals(station.activeSessionId());
    }

    private boolean isHuman(Jwt token) {
        return token != null && (access.isHumanClient(token.getClaimAsString("client_id"), userClientId)
            || access.isOperatorPoolClient(token.getClaimAsString("client_id")));
    }

    private static boolean isActive(WashSession session) {
        return session != null && session.getEstadoSesion() != SessionStatus.COMPLETADA
            && session.getEstadoSesion() != SessionStatus.EXPIRADA;
    }

    public record SessionView(String sessionId, String protocolo) {}
    public record OperatorStatus(StationOperatorAccessService.GrantView grant, SessionView activeSession) {}
    public record WebSocketTicketResponse(String ticket, String expiresAt) {}
}
