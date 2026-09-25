package com.handwash.service;

import com.handwash.agent.SessionAuditObserver;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.model.ProtocolType;
import com.handwash.strategy.ValidationRuleStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@Profile("cloud")
public class CloudStationSessionStarter {
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final SessionAuditService audit;
    private final SessionAuditObserver auditObserver;

    public CloudStationSessionStarter(StationCredentialService stations, SessionManager sessions,
                                      SessionAuditService audit, SessionAuditObserver auditObserver) {
        this.stations = stations;
        this.sessions = sessions;
        this.audit = audit;
        this.auditObserver = auditObserver;
    }

    public ResponseEntity<?> start(StationRecord station, SessionCreateRequest request, String requestId) {
        if (!isUuid(requestId)) return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of("ID_DE_SOLICITUD_INVALIDO"));
        if (request == null || !"2".equals(request.producerProtocolVersion())) {
            return ResponseEntity.status(409).body(ApiErrorResponse.producerVersionRequired());
        }
        if (station.activeSessionId() != null) {
            if (requestId.equals(station.activeRequestId())) {
                WashSession existing = sessions.getSesion(station.activeSessionId());
                if (isActive(existing)) return ResponseEntity.ok(responseFor(existing));
            }
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_CON_SESION_ACTIVA"));
        }

        final ProtocolType protocol;
        try {
            protocol = ProtocolType.fromRequestValue(request.protocolo());
        } catch (IllegalArgumentException invalidProtocol) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(invalidProtocol.getMessage()));
        }

        final String sessionId;
        try {
            sessionId = sessions.crearSesion(protocol, true);
        } catch (SessionCapacityException capacity) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_SESIONES"));
        } catch (IllegalArgumentException unavailableProtocol) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("PROTOCOLO_NO_DISPONIBLE"));
        }
        WashSession session = sessions.getSesion(sessionId);
        if (!stations.reserveSession(station.stationId(), sessionId, requestId)) {
            sessions.eliminarSesion(sessionId);
            StationRecord latest = stations.activeStation(station.stationId());
            if (latest != null && requestId.equals(latest.activeRequestId())) {
                WashSession concurrent = sessions.getSesion(latest.activeSessionId());
                if (isActive(concurrent)) return ResponseEntity.ok(responseFor(concurrent));
            }
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_CON_SESION_ACTIVA"));
        }
        try {
            audit.createEncounterForStation(session, station.stationId());
            auditObserver.registerSession(session);
            return ResponseEntity.ok(responseFor(session));
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException auditFailure) {
            stations.releaseSession(sessionId);
            sessions.eliminarSesion(sessionId);
            return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
        }
    }

    public SessionCreatedResponse responseFor(WashSession session) {
        ValidationRuleStrategy strategy = session.getEstrategia();
        return new SessionCreatedResponse(session.getSessionId(), session.getProtocolo().value(),
            sessions.getCodigoEmparejamiento(session.getSessionId()),
            sessions.getOwnerToken(session.getSessionId()),
            Instant.ofEpochMilli(sessions.getOwnerTokenExpirationEpochMs(session.getSessionId())).toString(),
            "Sesion creada. Conecta via WebSocket para recibir actualizaciones.",
            strategy.getMetodoObjetivo(), strategy.getAlcanceEvaluacion(),
            strategy.procedimientoCompletoValidado(), strategy.accionesNoDetectadas());
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
