package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.service.SessionAuditService;
import com.handwash.service.evidence.EvidenceStore;
import com.handwash.service.evidence.EvidenceSettingsService;
import com.handwash.service.evidence.WashEvidenceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only: the capture kill switch and on-demand viewing of a reported wash's photo, audited. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/evidence")
public class AdminEvidenceController {
    public record SwitchRequest(Boolean enabled) {}

    private final EvidenceSettingsService settings;
    private final WashEvidenceRepository repository;
    private final EvidenceStore store;
    private final SessionAuditService audit;

    public AdminEvidenceController(EvidenceSettingsService settings, WashEvidenceRepository repository,
                                   EvidenceStore store, SessionAuditService audit) {
        this.settings = settings;
        this.repository = repository;
        this.store = store;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> view(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_EVIDENCE_SETTINGS_READ", null);
            return ResponseEntity.ok(settings.view());
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @PutMapping
    public ResponseEntity<?> change(@AuthenticationPrincipal Jwt admin, @RequestBody(required = false) SwitchRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null || request.enabled() == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            return ResponseEntity.ok(settings.setEnabled(request.enabled(), admin.getSubject()));
        } catch (IllegalStateException notConfigured) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("FOTO_NO_CONFIGURADA"));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @GetMapping("/{sessionId}/{attemptNumber}/photo")
    public ResponseEntity<?> photo(@AuthenticationPrincipal Jwt admin, @PathVariable String sessionId,
                                   @PathVariable int attemptNumber) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (!sessionId.matches("(?i)[0-9a-f-]{36}") || attemptNumber < 1) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            var key = repository.findObjectKey(sessionId, attemptNumber);
            if (key.isEmpty()) return ResponseEntity.notFound().build();
            // Fail closed: a view that cannot be audited is not served.
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_EVIDENCE_PHOTO_VIEWED", sessionId);
            var bytes = store.get(key.get());
            if (bytes.isEmpty()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes.get());
        } catch (DataAccessException | IllegalArgumentException | EvidenceStore.EvidenceStoreException unavailable) {
            return unavailable();
        }
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("FOTO_NO_DISPONIBLE"));
    }
}
