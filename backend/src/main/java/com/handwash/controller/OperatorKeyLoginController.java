package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.KeyLoginRequest;
import com.handwash.api.v1.dto.KeyLoginResponse;
import com.handwash.model.StationRecord;
import com.handwash.security.ApiKeyService;
import com.handwash.service.StationCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public entry of the operator console: an ADMIN-issued key is traded for a short session. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/operator")
public final class OperatorKeyLoginController {
    private static final long SESSION_TTL_MS = 12 * 3_600_000L;
    private final ApiKeyService keys;
    private final StationCredentialService stations;

    public OperatorKeyLoginController(ApiKeyService keys, StationCredentialService stations) {
        this.keys = keys;
        this.stations = stations;
    }

    @PostMapping("/key-login")
    public ResponseEntity<?> login(@RequestBody(required = false) KeyLoginRequest request, HttpServletRequest http) {
        try {
            ApiKeyService.Login login = keys.login(request == null ? null : request.key(), com.handwash.security.ClientAddress.of(http));
            if (login.locked()) return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_KEY"));
            if (!login.accepted()) return ResponseEntity.status(401).body(ApiErrorResponse.of("KEY_NO_VALIDA"));
            StationRecord station = stations.activeStation(login.verification().stationId());
            if (station == null) return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_NO_DISPONIBLE"));
            boolean link = Boolean.TRUE.equals(request.linkStation());
            StationCredentialService.LinkResult linked = link ? stations.linkStationByKey(station.stationId(),
                ApiKeyService.subjectOf(login.verification().keyId()), Boolean.TRUE.equals(request.forceLink())) : null;
            return ResponseEntity.ok(new KeyLoginResponse(login.sessionToken(),
                System.currentTimeMillis() + SESSION_TTL_MS, station.stationId(), station.stationName(),
                login.verification().label(), linked == null ? null : linked.credential(),
                linked != null && linked.alreadyLinked()));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }
}
