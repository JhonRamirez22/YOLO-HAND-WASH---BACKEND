package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.model.WashOutcome;
import com.handwash.repository.FrictionAttemptRepository;
import com.handwash.service.SessionAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** ADMIN audit of finished washes: exact times, CORRECTO/REPORTADO and per-step outcome. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/wash-reports")
public final class AdminWashReportController {
    static final long DEFAULT_WINDOW_MS = TimeUnit.DAYS.toMillis(7);
    static final long MAX_WINDOW_MS = TimeUnit.DAYS.toMillis(93);
    private final FrictionAttemptRepository attempts;
    private final SessionAuditService audit;
    private final LongSupplier clock;

    @Autowired
    public AdminWashReportController(FrictionAttemptRepository attempts, SessionAuditService audit) {
        this(attempts, audit, System::currentTimeMillis);
    }

    AdminWashReportController(FrictionAttemptRepository attempts, SessionAuditService audit, LongSupplier clock) {
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<?> reports(@AuthenticationPrincipal Jwt admin,
                                     @RequestParam(required = false) Long desde,
                                     @RequestParam(required = false) Long hasta,
                                     @RequestParam(required = false) String resultado,
                                     @RequestParam(defaultValue = "50") int limit) {
        if (admin == null) return ResponseEntity.status(401).build();
        long to = hasta == null ? clock.getAsLong() + 1 : hasta;
        long from = desde == null ? to - DEFAULT_WINDOW_MS : desde;
        WashOutcome outcome;
        try {
            outcome = resultado == null || resultado.isBlank() ? null
                : WashOutcome.valueOf(resultado.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("RESULTADO_INVALIDO"));
        }
        if (limit < 1 || limit > 100 || from < 0 || from >= to || to - from > MAX_WINDOW_MS) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("FILTRO_INVALIDO"));
        }
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_WASH_REPORTS_READ", null);
            return ResponseEntity.ok(attempts.findReports(from, to, outcome, limit));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REPORTES_NO_DISPONIBLES"));
        }
    }
}
