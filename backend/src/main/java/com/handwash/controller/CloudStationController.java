package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import com.handwash.api.v1.dto.StationActivationRequest;
import com.handwash.api.v1.dto.StationEnrollmentRequest;
import com.handwash.api.v1.dto.StationEnrollmentResponse;
import com.handwash.api.v1.dto.StationStatusResponse;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.model.ProtocolType;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.CloudStationSessionStarter;
import com.handwash.service.SessionCapacityException;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("cloud")
@RequestMapping("/api/v1/stations")
public final class CloudStationController {
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final CloudStationSessionStarter sessionStarter;
    private final RequestRateLimiter rateLimiter;

    @Autowired
    public CloudStationController(StationCredentialService stations, SessionManager sessions,
                                  CloudStationSessionStarter sessionStarter,
                                  RequestRateLimiter rateLimiter) {
        this.stations = stations;
        this.sessions = sessions;
        this.sessionStarter = sessionStarter;
        this.rateLimiter = rateLimiter;
    }

    public CloudStationController(StationCredentialService stations, SessionManager sessions,
                                  com.handwash.service.SessionAuditService audit,
                                  com.handwash.agent.SessionAuditObserver auditObserver,
                                  RequestRateLimiter rateLimiter) {
        this(stations, sessions, new CloudStationSessionStarter(stations, sessions, audit, auditObserver),
            rateLimiter);
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@RequestBody(required = false) StationEnrollmentRequest request,
                                    HttpServletRequest httpRequest) {
        if (!rateLimiter.allowPairing(com.handwash.security.ClientAddress.of(httpRequest))) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        }
        try {
            StationCredentialService.EnrolledStation enrolled = stations.enroll(
                request == null ? null : request.code());
            if (enrolled == null) return ResponseEntity.status(401)
                .body(ApiErrorResponse.of("CODIGO_DE_INSCRIPCION_INVALIDO"));
            StationRecord station = enrolled.station();
            return ResponseEntity.ok(new StationEnrollmentResponse(station.stationId(),
                station.stationName(), enrolled.credential(), station.createdAtEpochMs()));
        } catch (DataAccessException storageFailure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REGISTRO_DE_PUESTO_NO_DISPONIBLE"));
        }
    }

    /** The Mac redeems the one-time token the ADMIN got when creating the station and keeps its credential. */
    @PostMapping("/activate")
    public ResponseEntity<?> activate(@RequestBody(required = false) StationActivationRequest request,
                                      HttpServletRequest httpRequest) {
        if (!rateLimiter.allowPairing(com.handwash.security.ClientAddress.of(httpRequest))) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        }
        try {
            StationCredentialService.EnrolledStation activated = stations.activate(
                request == null ? null : request.token());
            if (activated == null) return ResponseEntity.status(401)
                .body(ApiErrorResponse.of("ACTIVACION_INVALIDA_O_VENCIDA"));
            StationRecord station = activated.station();
            return ResponseEntity.ok(new StationEnrollmentResponse(station.stationId(),
                station.stationName(), activated.credential(), station.createdAtEpochMs()));
        } catch (DataAccessException storageFailure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REGISTRO_DE_PUESTO_NO_DISPONIBLE"));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.handwash.service.evidence.EvidenceSettingsService evidence;

    private boolean photoEvidenceActive() {
        try {
            return evidence != null && evidence.captureActive();
        } catch (DataAccessException unavailable) {
            return false;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> status(
        @RequestHeader(value = "X-Station-Credential", required = false) String credential) {
        StationRecord station = stations.authenticate(credential);
        if (station == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("PUESTO_NO_AUTORIZADO"));
        return ResponseEntity.ok(new StationStatusResponse(true, station.stationId(),
            station.stationName(), station.createdAtEpochMs(), station.activeSessionId(), null, photoEvidenceActive()));
    }

    @PostMapping("/session")
    public ResponseEntity<?> startSession(
        @RequestBody(required = false) SessionCreateRequest request,
        @RequestHeader(value = "X-Station-Credential", required = false) String credential,
        @RequestHeader(value = "X-Station-Request-Id", required = false) String requestId,
        HttpServletRequest httpRequest) {
        StationRecord station = stations.authenticate(credential);
        if (station == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("PUESTO_NO_AUTORIZADO"));
        if (!rateLimiter.allowSessionCreation(com.handwash.security.ClientAddress.of(httpRequest))) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_CREACION_SESIONES"));
        }
        return sessionStarter.start(station, request, requestId);
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume(
        @RequestHeader(value = "X-Station-Credential", required = false) String credential) {
        StationRecord station = stations.authenticate(credential);
        if (station == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("PUESTO_NO_AUTORIZADO"));
        if (station.activeSessionId() == null) return ResponseEntity.noContent().build();
        WashSession session = sessions.getSesion(station.activeSessionId());
        return isActive(session) ? ResponseEntity.ok(sessionStarter.responseFor(session))
            : ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_NO_RECUPERABLE"));
    }

    private static boolean isActive(WashSession session) {
        return session != null && session.getEstadoSesion() != SessionStatus.COMPLETADA
            && session.getEstadoSesion() != SessionStatus.EXPIRADA;
    }

    private static boolean isUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
