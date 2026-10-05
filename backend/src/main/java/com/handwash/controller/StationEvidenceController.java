package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.model.StationRecord;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import com.handwash.service.evidence.EvidenceUploadService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The kiosk uploads the retained frame of a reported attempt. It must present the station
 * credential and the session's owner capability, and the session must be the station's active one.
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/stations/evidence")
public class StationEvidenceController {
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final EvidenceUploadService uploads;

    public StationEvidenceController(StationCredentialService stations, SessionManager sessions,
                                     EvidenceUploadService uploads) {
        this.stations = stations;
        this.sessions = sessions;
        this.uploads = uploads;
    }

    @PostMapping("/{sessionId}/{attemptNumber}")
    public ResponseEntity<?> upload(@PathVariable String sessionId, @PathVariable int attemptNumber,
        @RequestHeader(value = "X-Station-Credential", required = false) String credential,
        @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
        @RequestBody(required = false) byte[] image) {
        StationRecord station = stations.authenticate(credential);
        if (station == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("PUESTO_NO_AUTORIZADO"));
        if (!sessionId.matches("(?i)[0-9a-f-]{36}") || attemptNumber < 1 || attemptNumber > 100_000
            || !sessionId.equals(station.activeSessionId())) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        SessionManager.AccessInfo access = sessions.autenticar(sessionId, sessionToken);
        if (access == null || access.role() != SessionManager.AccessRole.OWNER) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (image == null || image.length == 0) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("IMAGEN_INVALIDA"));
        }
        return switch (uploads.upload(sessionId, attemptNumber, image)) {
            case STORED -> ResponseEntity.ok(Map.of("stored", true));
            case DISABLED -> ResponseEntity.status(403).body(ApiErrorResponse.of("FOTO_DESACTIVADA"));
            case NOT_ELIGIBLE -> ResponseEntity.status(409).body(ApiErrorResponse.of("FOTO_NO_ELEGIBLE"));
            case ALREADY_STORED -> ResponseEntity.status(409).body(ApiErrorResponse.of("FOTO_YA_REGISTRADA"));
            case INVALID_IMAGE -> ResponseEntity.badRequest().body(ApiErrorResponse.of("IMAGEN_INVALIDA"));
            case UNAVAILABLE -> ResponseEntity.status(503).body(ApiErrorResponse.of("FOTO_NO_DISPONIBLE"));
        };
    }
}
