package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.ProducerBootstrapRequest;
import com.handwash.api.v1.dto.ProducerBootstrapResponse;
import com.handwash.api.v1.dto.ProducerBuildIdentityRequest;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** M2M-only exchange: binds the private RTX client to its registered station session. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/producer")
public final class ProducerBootstrapController {
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final SessionAuditService audit;

    public ProducerBootstrapController(StationCredentialService stations,
        SessionManager sessions, SessionAuditService audit) {
        this.stations = stations;
        this.sessions = sessions;
        this.audit = audit;
    }

    @PostMapping("/session")
    public ResponseEntity<?> bootstrap(@RequestBody(required = false) ProducerBootstrapRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        if (request == null || jwt == null
            || request.stationId() != null && !isUuid(request.stationId())) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("PRODUCTOR_REQUEST_INVALID"));
        }
        String clientId = jwt.getClaimAsString("client_id");
        // No station id needed: the producer identity (its API key) is bound to exactly one station.
        StationRecord station = request.stationId() == null
            ? stations.activeStationByProducerClient(clientId)
            : stations.activeStation(request.stationId());
        if (station == null || clientId == null || !clientId.equals(station.producerClientId())) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("PRODUCTOR_PUESTO_NO_AUTORIZADO"));
        }
        if (station.activeSessionId() == null) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("NO_ACTIVE_SESSION"));
        }

        try {
            audit.validateProducerBuild(new ProducerBuildIdentityRequest(
                request.modelVersion(), request.modelSha256(), request.datasetVersion()));
        } catch (IllegalArgumentException unreviewedBuild) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PRODUCTOR_RELEASE_NO_COINCIDE"));
        }

        String sessionId = station.activeSessionId();
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return ResponseEntity.status(409)
            .body(ApiErrorResponse.of("SESION_NO_RECUPERABLE"));

        SessionManager.TokenIssue token;
        SessionManager.ProducerEpochResult epoch;
        synchronized (session) {
            StationRecord current = stations.activeStation(station.stationId());
            if (sessions.getSesion(sessionId) != session || current == null
                || !sessionId.equals(current.activeSessionId())
                || !clientId.equals(current.producerClientId())
                || session.getEstadoSesion() == SessionStatus.COMPLETADA
                || session.getEstadoSesion() == SessionStatus.EXPIRADA) {
                return ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_NO_RECUPERABLE"));
            }
            token = sessions.emitirTokenDispositivo(sessionId);
            if (token == null) return ResponseEntity.status(409)
                .body(ApiErrorResponse.of("SESION_NO_RECUPERABLE"));
            epoch = sessions.registrarProducerEpoch(sessionId, token.value());
        }
        if (epoch.outcome() != SessionManager.ProducerEpochOutcome.REGISTERED) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PRODUCTOR_EPOCH_NO_REGISTRADO"));
        }
        try {
            audit.recordProducerEpoch(sessionId, clientId,
                epoch.registration().producerEpoch(), epoch.registration().rotated());
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
        }
        return ResponseEntity.ok(new ProducerBootstrapResponse(sessionId, token.value(),
            Instant.ofEpochMilli(token.expiresAtEpochMs()).toString(),
            epoch.registration().producerEpoch(), epoch.registration().protocolVersion()));
    }

    private static boolean isUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
