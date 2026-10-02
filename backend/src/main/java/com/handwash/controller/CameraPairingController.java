package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.WebRtcTicketResponse;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.security.CameraPairingRegistry;
import com.handwash.security.SyntheticTokens;
import com.handwash.security.WebRtcTicketService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.webrtc.IceServerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connects a phone camera to a station by QR. The panel asks for a one-time code, the phone trades it for a
 * camera-only token, and with that token it fetches a fresh video ticket for whatever session the station has
 * open (sessions renew by themselves). The token cannot read the panel, the console or ADMIN.
 */
@RestController
@Profile("cloud")
public class CameraPairingController {
    private static final Logger log = LoggerFactory.getLogger(CameraPairingController.class);
    private static final String SUBJECT_PREFIX = "apikey_";

    public record RedeemRequest(String code) {}

    private final CameraPairingRegistry pairing;
    private final StationOperatorAccessService access;
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final WebRtcTicketService tickets;
    private final IceServerProvider iceServers;

    public CameraPairingController(CameraPairingRegistry pairing, StationOperatorAccessService access,
                                   StationCredentialService stations, SessionManager sessions,
                                   WebRtcTicketService tickets, IceServerProvider iceServers) {
        this.pairing = pairing;
        this.access = access;
        this.stations = stations;
        this.sessions = sessions;
        this.tickets = tickets;
        this.iceServers = iceServers;
    }

    /** Authenticated: only a console signed in for a station can show the QR of that station. */
    @PostMapping("/api/v1/operator/camera-pair")
    @Transactional
    public ResponseEntity<?> pair(@AuthenticationPrincipal Jwt operator) {
        if (operator == null || !SyntheticTokens.CONSOLE_CLIENT_ID.equals(operator.getClaimAsString("client_id"))
            || operator.getSubject() == null || !operator.getSubject().startsWith(SUBJECT_PREFIX)) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        StationOperatorAccessService.GrantView grant = access.activeGrantForUpdate(operator.getSubject());
        StationRecord station = grant == null ? null : stations.activeStation(grant.stationId());
        if (station == null) return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_NO_DISPONIBLE"));
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", pairing.issueCode(station.stationId()));
            body.put("expiresInSeconds", 120);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADOS_CODIGOS_DE_CAMARA"));
        }
    }

    /** Public: the one-time code is the credential. */
    @PostMapping("/api/v1/camera/redeem")
    public ResponseEntity<?> redeem(@RequestBody(required = false) RedeemRequest request) {
        if (request == null || request.code() == null || request.code().isBlank() || request.code().length() > 100) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        CameraPairingRegistry.PairedCamera camera = pairing.redeem(request.code().trim());
        if (camera == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("CODIGO_DE_CAMARA_NO_VALIDO"));
        StationRecord station = stations.activeStation(camera.stationId());
        if (station == null) return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_NO_DISPONIBLE"));
        log.info("Cámara emparejada con el puesto \"{}\"", station.stationName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cameraToken", camera.token());
        body.put("stationName", station.stationName());
        body.put("expiresInSeconds", 12 * 3_600);
        return ResponseEntity.ok(body);
    }

    /** Cheap poll for the phone: which session the station has open now (no ticket is issued). */
    @PostMapping("/api/v1/camera/state")
    public ResponseEntity<?> state(@RequestHeader(value = "X-Camera-Token", required = false) String token) {
        String stationId = pairing.authenticate(token);
        if (stationId == null) {
            return pairing.isReplaced(token)
                ? ResponseEntity.status(409).body(ApiErrorResponse.of("CAMARA_REEMPLAZADA"))
                : ResponseEntity.status(401).body(ApiErrorResponse.of("CAMARA_NO_AUTORIZADA"));
        }
        StationRecord station = stations.activeStation(stationId);
        String sessionId = station == null ? null : station.activeSessionId();
        if (sessionId == null || sessions.getSesion(sessionId) == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /** 200 with a ticket for the active session, 204 while the station has none, 409 when replaced, 401 otherwise. */
    @PostMapping("/api/v1/camera/ticket")
    public ResponseEntity<?> ticket(@RequestHeader(value = "X-Camera-Token", required = false) String token) {
        String stationId = pairing.authenticate(token);
        if (stationId == null) {
            return pairing.isReplaced(token)
                ? ResponseEntity.status(409).body(ApiErrorResponse.of("CAMARA_REEMPLAZADA"))
                : ResponseEntity.status(401).body(ApiErrorResponse.of("CAMARA_NO_AUTORIZADA"));
        }
        StationRecord station = stations.activeStation(stationId);
        String sessionId = station == null ? null : station.activeSessionId();
        WashSession session = sessionId == null ? null : sessions.getSesion(sessionId);
        if (session == null) return ResponseEntity.noContent().build();
        var result = tickets.issue(sessionId, sessions.getOwnerToken(sessionId));
        return switch (result.outcome()) {
            case NOT_FOUND -> ResponseEntity.noContent().build();
            case UNAUTHORIZED -> ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            case CAPACITY -> ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_TICKETS_WEBRTC"));
            case ISSUED -> {
                log.info("Video autorizado al celular para la sesión {}", sessionId.substring(0, 8));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sessionId", sessionId);
                body.put("ticket", new WebRtcTicketResponse(result.issued().value(), result.role().name(),
                    Instant.ofEpochMilli(result.issued().expiresAtMs()).toString(), iceServers.servers(sessionId, result.role())));
                yield ResponseEntity.ok(body);
            }
        };
    }
}
