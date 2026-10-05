package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.service.SessionAuditService;
import com.handwash.service.audit.AuditChainVerifier;
import com.handwash.service.audit.AuditSealService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only (see CloudSecurityConfig): integrity check of the audit log and its daily seals. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/audit-chain")
public final class AdminAuditChainController {
    public record Status(AuditChainVerifier.Report chain, boolean resultsMatchLatestSeal,
                         List<AuditSealService.Seal> seals) {}

    private final AuditChainVerifier verifier;
    private final AuditSealService seals;
    private final SessionAuditService audit;

    public AdminAuditChainController(AuditChainVerifier verifier, AuditSealService seals, SessionAuditService audit) {
        this.verifier = verifier;
        this.seals = seals;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> verify(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            // Verified first, audited after: the audit event of this very read must not hide a break.
            var report = verifier.verify();
            var recent = seals.recent(5);
            boolean resultsMatch = recent.isEmpty() || seals.resultsStillMatch(recent.get(0));
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_AUDIT_CHAIN_VERIFIED", null);
            return ResponseEntity.ok(new Status(report, resultsMatch, recent));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @PostMapping("/seal")
    public ResponseEntity<?> seal(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            var created = seals.sealIfNeeded();
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_AUDIT_SEALED", null);
            return created.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
        } catch (DataAccessException | IllegalStateException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("AUDITORIA_NO_DISPONIBLE"));
    }
}
