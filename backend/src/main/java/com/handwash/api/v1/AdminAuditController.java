package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.service.SessionAuditService;
import com.handwash.repository.FailedAttemptRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only administrator access to metadata audit records; media is deliberately unsupported. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin")
public final class AdminAuditController {
    private static final int MAX_PAGE_SIZE = 100;
    private final SessionAuditService audit;
    private final FailedAttemptRepository failedAttempts;

    public AdminAuditController(SessionAuditService audit, FailedAttemptRepository failedAttempts) {
        this.audit = audit;
        this.failedAttempts = failedAttempts;
    }

    @GetMapping("/encounters")
    public ResponseEntity<?> encounters(@AuthenticationPrincipal Jwt admin,
                                        @RequestParam(defaultValue = "50") int limit) {
        if (admin == null || !validLimit(limit)) return badRequest();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_ENCOUNTERS_LISTED", null);
            return ResponseEntity.ok(audit.recentEncounters(limit));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @GetMapping("/encounters/{sessionId}/events")
    public ResponseEntity<?> events(@AuthenticationPrincipal Jwt admin,
                                    @PathVariable String sessionId,
                                    @RequestParam(required = false) Long beforeEventId,
                                    @RequestParam(defaultValue = "50") int limit) {
        if (admin == null || !validSessionId(sessionId) || !validLimit(limit)
            || beforeEventId != null && beforeEventId <= 0) return badRequest();
        try {
            if (!audit.encounterExists(sessionId)) return ResponseEntity.notFound().build();
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_EVENTS_READ", sessionId);
            return ResponseEntity.ok(audit.events(sessionId, beforeEventId, limit));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @GetMapping("/access-events")
    public ResponseEntity<?> accessEvents(@AuthenticationPrincipal Jwt admin,
                                          @RequestParam(defaultValue = "50") int limit) {
        if (admin == null || !validLimit(limit)) return badRequest();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_ACCESS_LOG_READ", null);
            return ResponseEntity.ok(audit.adminAccessEvents(limit));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @GetMapping("/failed-attempts")
    public ResponseEntity<?> failedAttempts(@AuthenticationPrincipal Jwt admin,
                                            @RequestParam(defaultValue = "50") int limit) {
        if (admin == null || !validLimit(limit)) return badRequest();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_FAILED_ATTEMPTS_READ", null);
            return ResponseEntity.ok(failedAttempts.findRecentAuditRecords(limit));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    private static boolean validLimit(int limit) {
        return limit >= 1 && limit <= MAX_PAGE_SIZE;
    }

    private static boolean validSessionId(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static ResponseEntity<ApiErrorResponse> badRequest() {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("PARAMETROS_INVALIDOS"));
    }

    private static ResponseEntity<ApiErrorResponse> unavailable() {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
    }
}
