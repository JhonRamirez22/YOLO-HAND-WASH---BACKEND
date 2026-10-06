package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.model.WashOutcome;
import com.handwash.repository.FrictionAttemptRepository;
import com.handwash.service.SessionAuditService;
import com.handwash.service.WashReportAnalytics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** ADMIN-only trends by day and shift and an anonymous CSV export; every read is audited. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/wash-stats")
public final class AdminWashStatsController {
    static final long MAX_WINDOW_MS = TimeUnit.DAYS.toMillis(93);
    static final int MAX_ROWS = 20_000;
    private final FrictionAttemptRepository attempts;
    private final SessionAuditService audit;
    private final ZoneId zone;
    private final LongSupplier clock;

    @Autowired
    public AdminWashStatsController(FrictionAttemptRepository attempts, SessionAuditService audit,
                                    @Value("${handwash.reports.zone:America/Bogota}") String zone) {
        this(attempts, audit, ZoneId.of(zone), System::currentTimeMillis);
    }

    AdminWashStatsController(FrictionAttemptRepository attempts, SessionAuditService audit, ZoneId zone, LongSupplier clock) {
        this.attempts = attempts;
        this.audit = audit;
        this.zone = zone;
        this.clock = clock;
    }

    @GetMapping("/trends")
    public ResponseEntity<?> trends(@AuthenticationPrincipal Jwt admin, @RequestParam(required = false) Long desde,
                                    @RequestParam(required = false) Long hasta) {
        if (admin == null) return ResponseEntity.status(401).build();
        long to = hasta == null ? clock.getAsLong() + 1 : hasta;
        long from = desde == null ? to - TimeUnit.DAYS.toMillis(7) : desde;
        if (!validWindow(from, to)) return ResponseEntity.badRequest().body(ApiErrorResponse.of("FILTRO_INVALIDO"));
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_WASH_TRENDS_READ", null);
            var rows = attempts.findExportRows(from, to, null, MAX_ROWS + 1);
            if (rows.size() > MAX_ROWS) return ResponseEntity.badRequest().body(ApiErrorResponse.of("RANGO_DEMASIADO_GRANDE"));
            return ResponseEntity.ok(WashReportAnalytics.tendencias(rows, zone));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REPORTES_NO_DISPONIBLES"));
        }
    }

    @GetMapping("/export.csv")
    public ResponseEntity<?> export(@AuthenticationPrincipal Jwt admin, @RequestParam(required = false) Long desde,
                                    @RequestParam(required = false) Long hasta, @RequestParam(required = false) String resultado) {
        if (admin == null) return ResponseEntity.status(401).build();
        long to = hasta == null ? clock.getAsLong() + 1 : hasta;
        long from = desde == null ? to - TimeUnit.DAYS.toMillis(7) : desde;
        WashOutcome outcome;
        try {
            outcome = resultado == null || resultado.isBlank() ? null : WashOutcome.valueOf(resultado.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("RESULTADO_INVALIDO"));
        }
        if (!validWindow(from, to)) return ResponseEntity.badRequest().body(ApiErrorResponse.of("FILTRO_INVALIDO"));
        try {
            // Fail closed: an export that cannot be audited is not produced.
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_WASH_REPORTS_EXPORTED", null);
            var rows = attempts.findExportRows(from, to, outcome, MAX_ROWS + 1);
            if (rows.size() > MAX_ROWS) return ResponseEntity.badRequest().body(ApiErrorResponse.of("RANGO_DEMASIADO_GRANDE"));
            String name = "handwash-lavados-" + day(from) + "_" + day(to - 1) + ".csv";
            return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(WashReportAnalytics.csv(rows, zone));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REPORTES_NO_DISPONIBLES"));
        }
    }

    private String day(long epochMs) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    private static boolean validWindow(long from, long to) {
        return from >= 0 && from < to && to - from <= MAX_WINDOW_MS;
    }
}
