package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.KeyLoginResponse;
import com.handwash.model.StationRecord;
import com.handwash.security.ApiKeyService;
import com.handwash.security.PhoneLinkRegistry;
import com.handwash.security.SyntheticTokens;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens the operator console on a phone from a console that is already signed in: the console asks for a
 * one-time code (shown as a QR) and the phone trades it for its own session. The key never reaches the phone.
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/operator")
public final class OperatorPhoneLinkController {
    private static final long SESSION_TTL_MS = 12 * 3_600_000L;
    private static final String SUBJECT_PREFIX = "apikey_";

    public record RedeemRequest(String code) {}

    private final ApiKeyService keys;
    private final StationCredentialService stations;
    private final PhoneLinkRegistry links;

    public OperatorPhoneLinkController(ApiKeyService keys, StationCredentialService stations, PhoneLinkRegistry links) {
        this.keys = keys;
        this.stations = stations;
        this.links = links;
    }

    /** Authenticated: only a console signed in with an API key can open a phone. */
    @PostMapping("/phone-link")
    public ResponseEntity<?> issueLink(@AuthenticationPrincipal Jwt operator) {
        if (operator == null || !SyntheticTokens.CONSOLE_CLIENT_ID.equals(operator.getClaimAsString("client_id"))
            || operator.getSubject() == null || !operator.getSubject().startsWith(SUBJECT_PREFIX)) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", links.issue(operator.getSubject().substring(SUBJECT_PREFIX.length())));
            body.put("expiresInSeconds", 120);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADOS_ENLACES_DE_CELULAR"));
        }
    }

    /** Public: the one-time code is the credential. */
    @PostMapping("/phone-login")
    public ResponseEntity<?> redeem(@RequestBody(required = false) RedeemRequest request) {
        if (request == null || request.code() == null || request.code().isBlank() || request.code().length() > 100) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            PhoneLinkRegistry.Redeemed redeemed = links.redeem(request.code().trim());
            ApiKeyService.Login login = redeemed == null ? null : keys.sessionForKey(redeemed.keyId());
            if (login == null || !login.accepted()) {
                return ResponseEntity.status(401).body(ApiErrorResponse.of("ENLACE_NO_VALIDO"));
            }
            StationRecord station = stations.activeStation(login.verification().stationId());
            if (station == null) return ResponseEntity.status(409).body(ApiErrorResponse.of("PUESTO_NO_DISPONIBLE"));
            // An ADMIN link for the station's Mac also makes that Mac the station screen (replacing the old one on purpose).
            StationCredentialService.LinkResult linked = redeemed.linkStation()
                ? stations.linkStationByKey(station.stationId(), ApiKeyService.subjectOf(login.verification().keyId()), true) : null;
            return ResponseEntity.ok(new KeyLoginResponse(login.sessionToken(), System.currentTimeMillis() + SESSION_TTL_MS,
                station.stationId(), station.stationName(), login.verification().label(),
                linked == null ? null : linked.credential(), linked != null && linked.alreadyLinked()));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }
}
