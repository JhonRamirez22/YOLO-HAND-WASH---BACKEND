package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.ApiKeyCreateRequest;
import com.handwash.api.v1.dto.ApiKeyCreatedResponse;
import com.handwash.api.v1.dto.ApiKeyRecord;
import com.handwash.model.StationRecord;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.service.StationCredentialService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** ADMIN-only: the only place API keys come from. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/api-keys")
public final class AdminApiKeyController {
    private final ApiKeyService keys;
    private final StationCredentialService stations;

    public AdminApiKeyController(ApiKeyService keys, StationCredentialService stations) {
        this.keys = keys;
        this.stations = stations;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            Map<String, StationRecord> byId = stations.listStations().stream()
                .collect(Collectors.toMap(StationRecord::stationId, Function.identity()));
            List<ApiKeyRecord> records = keys.list().stream().map(key -> new ApiKeyRecord(key.keyId(), key.stationId(),
                byId.containsKey(key.stationId()) ? byId.get(key.stationId()).stationName() : "Puesto desconocido",
                key.label(), key.scopes().stream().map(Enum::name).sorted().toList(), key.createdBy(),
                key.createdAtEpochMs(), key.lastUsedAtEpochMs(), key.lastUsedIp(), key.revokedAtEpochMs())).toList();
            return ResponseEntity.ok(records);
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt admin, @RequestBody(required = false) ApiKeyCreateRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null || request.scopes() == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
            for (String scope : request.scopes()) scopes.add(ApiKeyScope.valueOf(scope));
            ApiKeyService.IssuedKey issued = keys.issue(request.stationId(), request.label(), scopes, admin.getSubject());
            return ResponseEntity.ok(new ApiKeyCreatedResponse(issued.keyId(), issued.key(), request.stationId(),
                request.label().trim(), scopes.stream().map(Enum::name).sorted().toList()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        } catch (IllegalStateException limit) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("LIMITE_DE_KEYS_POR_PUESTO"));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<?> revoke(@AuthenticationPrincipal Jwt admin, @PathVariable String keyId) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            return keys.revoke(keyId, admin.getSubject()) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }
}
