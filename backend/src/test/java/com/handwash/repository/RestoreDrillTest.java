package com.handwash.repository;

import com.handwash.model.SessionAuditEvent;
import com.handwash.service.audit.AuditChainHasher;
import com.handwash.service.audit.AuditChainVerifier;
import com.handwash.service.audit.AuditSealService;
import com.handwash.service.audit.UnavailableAuditSealStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in restore rehearsal driven by scripts/chaos/restore-drill.sh against disposable local PostgreSQL:
 * HANDWASH_DRILL=seed fills a database, the script dumps and restores it, HANDWASH_DRILL=verify proves the
 * restored copy has the same schema history, rows, audit chain and seal. Nothing here touches AWS.
 */
@EnabledIfEnvironmentVariable(named = "HANDWASH_DRILL", matches = "seed|verify")
@EnabledIfEnvironmentVariable(named = "HANDWASH_TEST_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_0-9]+")
class RestoreDrillTest {
    private static final int EVENTS = 500;
    private final DriverManagerDataSource ds =
        new DriverManagerDataSource(System.getenv("HANDWASH_TEST_PG_URL"), "handwash_test", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final AuditChainHasher hasher = new AuditChainHasher("drill-secret-" + "x".repeat(40));

    @Test
    void seedOrVerify() {
        if ("seed".equals(System.getenv("HANDWASH_DRILL"))) seed(); else verify();
    }

    private void seed() {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var repo = new SessionAuditRepository(jdbc, hasher);
        for (int i = 0; i < EVENTS; i++) {
            repo.appendEvent(new SessionAuditEvent(0, null, "STEP_CONFIRMED", "SYSTEM", "PASO_1_PALMAS", "ACCEPTED",
                null, i, null, null, 1_000L + i));
        }
        jdbc.update("""
            INSERT INTO friction_attempt_validation (session_id, attempt_number, policy_version, revision, status, terminal,
                created_at_epoch_ms, updated_at_epoch_ms, finished_at_epoch_ms, started_at_epoch_ms, duration_ms, result, outcome)
            VALUES ('11111111-1111-1111-1111-111111111111', 1, 'V2', 1, 'DONE', TRUE, 1, 5, 5, 1, 40000,
                    'FRICTION_SEQUENCE_COMPLETE', 'CORRECTO')""");
        new AuditSealService(jdbc, hasher, new UnavailableAuditSealStore()).sealIfNeeded().orElseThrow();
    }

    private void verify() {
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.validate();
        assertEquals("12", flyway.info().current().getVersion().getVersion());
        assertEquals(EVENTS, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_hash IS NOT NULL", Integer.class));
        var report = new AuditChainVerifier(jdbc, hasher).verify();
        assertTrue(report.ok(), report.problems().toString());
        assertTrue(report.matchesLatestSeal(), "the restored copy must still match its seal");
        var seals = new AuditSealService(jdbc, hasher, new UnavailableAuditSealStore());
        assertTrue(seals.resultsStillMatch(seals.recent(1).get(0)), "finished washes must match the sealed digest");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation", Integer.class));
        System.out.println("RESTORE_DRILL_VERIFIED events=" + report.checked() + " chain=ok seal=ok");
    }
}
