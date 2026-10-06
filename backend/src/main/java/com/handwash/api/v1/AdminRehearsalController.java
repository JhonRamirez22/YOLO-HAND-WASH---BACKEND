package com.handwash.api.v1;

import com.handwash.repository.SessionAuditRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only: how often operators confirmed or corrected each detected step in rehearsal mode. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/rehearsal")
public final class AdminRehearsalController {
    private final SessionAuditRepository audit;

    public AdminRehearsalController(SessionAuditRepository audit) { this.audit = audit; }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(audit.rehearsalSummary());
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).build();
        }
    }
}
