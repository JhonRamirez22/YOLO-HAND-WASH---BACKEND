package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.model.StationRecord;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.PhoneLinkRegistry;
import com.handwash.service.StationCredentialService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ADMIN hands one link to a wash station and it works: opening it signs the device into that station's console with
 * no key to type, and the Mac's link also makes it the station screen. Links work once and expire; one console key per
 * station ("Enlace del puesto") is reused, so issuing many links never exhausts the key limit.
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/stations")
public class AdminStationLinkController {
    static final String LINK_LABEL = "Enlace del puesto";
    static final int DEFAULT_MINUTES = 60;

    public record LinkRequest(Integer ttlMinutes, Boolean mac) {}

    private final ApiKeyService keys;
    private final StationCredentialService stations;
    private final PhoneLinkRegistry links;

    public AdminStationLinkController(ApiKeyService keys, StationCredentialService stations, PhoneLinkRegistry links) {
        this.keys = keys;
        this.stations = stations;
        this.links = links;
    }

    @PostMapping("/{stationId}/operator-link")
    public ResponseEntity<?> issue(@AuthenticationPrincipal Jwt admin, @PathVariable String stationId,
                                   @RequestBody(required = false) LinkRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            UUID.fromString(stationId);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            StationRecord station = stations.activeStation(stationId);
            if (station == null) return ResponseEntity.notFound().build();
            int minutes = request == null || request.ttlMinutes() == null ? DEFAULT_MINUTES
                : Math.max(1, Math.min(1_440, request.ttlMinutes()));
            boolean mac = request != null && Boolean.TRUE.equals(request.mac());
            String keyId = linkKeyOf(stationId, admin.getSubject());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", links.issue(keyId, minutes * 60_000L, mac));
            body.put("expiresInSeconds", minutes * 60);
            body.put("mac", mac);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADOS_ENLACES_DE_PUESTO"));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }

    /** The station's reusable console key for links; created the first time, or again if ADMIN revoked it. */
    private String linkKeyOf(String stationId, String adminSubject) {
        return keys.list().stream()
            .filter(key -> key.stationId().equals(stationId) && LINK_LABEL.equals(key.label())
                && key.revokedAtEpochMs() == null && key.scopes().contains(ApiKeyScope.CONSOLE))
            .map(ApiKeyService.KeyView::keyId).findFirst()
            .orElseGet(() -> keys.issue(stationId, LINK_LABEL, EnumSet.of(ApiKeyScope.CONSOLE), adminSubject).keyId());
    }
}
