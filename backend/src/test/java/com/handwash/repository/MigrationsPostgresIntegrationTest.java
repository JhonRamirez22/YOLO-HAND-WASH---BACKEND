package com.handwash.repository;

import com.handwash.model.*;
import com.handwash.service.ProtocolProfileService;
import com.handwash.service.SessionAuditService;
import com.handwash.strategy.BalancedFrictionStrategy;
import com.handwash.strategy.ProtocolProfileStrategy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: applies V1-V8 with Flyway on a disposable local PostgreSQL and exercises V7/V8. */
@EnabledIfEnvironmentVariable(named = "HANDWASH_TEST_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/handwash_test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationsPostgresIntegrationTest {
    private static final DriverManagerDataSource DS =
        new DriverManagerDataSource(System.getenv("HANDWASH_TEST_PG_URL"), "handwash_test", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(DS);

    @Test @Order(1) void flywayAppliesAndValidatesAllSeventeenMigrations() {
        var flyway = Flyway.configure().dataSource(DS).locations("classpath:db/migration").load();
        var result = flyway.migrate();
        flyway.validate();
        assertEquals("17", flyway.info().current().getVersion().getVersion());
        assertEquals(17, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
    }

    @Test @Order(2) void protocolProfilesSeedAppendAuditAndConstraints() {
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var service = new ProtocolProfileService(new ProtocolProfileRepository(jdbc), new com.handwash.repository.ChangeControlRepository(jdbc), audit, new DataSourceTransactionManager(DS));
        assertEquals(ProtocolProfile.PREDETERMINADO.tiemposPasoMs(), service.perfilVigente().tiemposPasoMs());
        assertEquals(1, service.perfilVigente().version());
        var v2 = service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-sub", "Ajuste de prueba en PostgreSQL").perfil();
        assertEquals(2, v2.version());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_type='PROTOCOL_PROFILE_VERSION_CREATED' AND reason_code='VERSION_2'", Integer.class));
        assertThrows(DuplicateKeyException.class, () -> new ProtocolProfileRepository(jdbc).insert(v2, "duplicado", null, "ACTIVE"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO protocol_profiles VALUES (9, 2000, 7000, 7000, 7000, 7000, 7000, 60000, 'x', 0)"));

        String sessionId = UUID.randomUUID().toString();
        var strategy = new BalancedFrictionStrategy(new ProtocolProfileStrategy(v2));
        audit.createEncounter(new WashSession(sessionId, ProtocolType.AGUA_Y_JABON, strategy));
        assertEquals(2, jdbc.queryForObject("SELECT protocol_profile_version FROM wash_encounters WHERE session_id=?", Integer.class, sessionId));
    }

    private static List<FrictionEvaluation.Step> steps(int validated) {
        return BalancedFrictionStrategy.STEPS.stream().map(s -> new FrictionEvaluation.Step(s.name(),
            s.getNumero() <= validated ? FrictionEvaluation.StepStatus.VALIDADO : FrictionEvaluation.StepStatus.PENDIENTE,
            s.getNumero() <= validated ? 7000 : 0, 7000, null)).toList();
    }

    @Test @Order(3) void washReportsOutcomesEvidenceAndChecks() {
        var repo = new FrictionAttemptRepository(jdbc);
        String good = UUID.randomUUID().toString(), left = UUID.randomUUID().toString(), active = UUID.randomUUID().toString();
        repo.save(new FrictionAttemptRecord(good, 1, BalancedFrictionStrategy.VERSION, 1, "ACTIVO", null, null, false, steps(0), 1_000L, null, null, false, null, null));
        repo.save(new FrictionAttemptRecord(good, 1, BalancedFrictionStrategy.VERSION, 2, "COMPLETO_PARCIAL", "FRICTION_SEQUENCE_COMPLETE", null, true, steps(6), 1_000L, 66_000L, 65_000L, true, WashOutcome.CORRECTO, null));
        repo.save(new FrictionAttemptRecord(left, 1, BalancedFrictionStrategy.VERSION, 1, "INTERRUMPIDO", "INTERRUPTED", "HANDS_ABSENT", true, steps(2), 2_000L, 20_000L, 18_000L, false, WashOutcome.REPORTADO, "DESACTIVADA"));
        repo.save(new FrictionAttemptRecord(active, 1, BalancedFrictionStrategy.VERSION, 1, "ACTIVO", null, null, false, steps(1), 3_000L, null, null, false, null, null));

        var reported = repo.findReports(0, 100_000, WashOutcome.REPORTADO, 10);
        assertEquals(List.of(left), reported.stream().map(WashReportRecord::sessionId).toList());
        assertEquals("DESACTIVADA", reported.get(0).estadoEvidencia());
        var exported = repo.findExportRows(0, 100_000, null, 10);
        assertEquals(2, exported.size(), "export query runs on PostgreSQL too");
        assertEquals(1, repo.findExportRows(0, 100_000, WashOutcome.REPORTADO, 10).size());
        var correct = repo.findReports(0, 100_000, WashOutcome.CORRECTO, 10).get(0);
        assertTrue(correct.lento());
        assertEquals(65_000L, correct.duracionMs());
        assertEquals(6, correct.pasos().size());

        assertEquals(1, repo.interruptAfterRestart());
        assertEquals("INTERRUMPIDO", jdbc.queryForObject("SELECT outcome FROM friction_attempt_validation WHERE session_id=?", String.class, active));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE friction_attempt_validation SET outcome='APROBADO' WHERE session_id=?", good));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE friction_attempt_validation SET evidence_status='FOTO' WHERE session_id=?", left));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE friction_attempt_validation SET duration_ms=-1 WHERE session_id=?", left));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO wash_report_evidence VALUES (?, 1, 'k', ?, 10, 5)", left, "a".repeat(64)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO wash_report_evidence VALUES (?, 1, 'k', ?, 10, 20)", UUID.randomUUID().toString(), "a".repeat(64)));
        assertEquals(1, jdbc.update("INSERT INTO wash_report_evidence VALUES (?, 1, 'reports/x.jpg.enc', ?, 10, 20)", left, "a".repeat(64)));
    }

    @Test @Order(4) void stationShiftsAllowOneOpenShiftPerStationAndRejectInvalidRows() {
        String station = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO handwash_stations (station_id, station_name, credential_hash, created_at_epoch_ms, created_by) VALUES (?,?,?,?,?)",
            station, "Puesto", "h".repeat(64), 1L, "admin");
        var repo = new StationShiftRepository(jdbc);
        var open = new StationShift(UUID.randomUUID().toString(), station, "AGUA_Y_JABON", "op", 1_000L, 9_000L, null, null);
        repo.insert(open);

        assertEquals(open, repo.findOpenByStation(station).orElseThrow());
        assertThrows(DuplicateKeyException.class, () -> repo.insert(new StationShift(UUID.randomUUID().toString(),
            station, "AGUA_Y_JABON", "op", 2_000L, 9_000L, null, null)), "second open shift for the same station");
        assertEquals(1, repo.close(open.shiftId(), 5_000L, StationShift.ShiftEndReason.STOPPED));
        assertEquals(0, repo.close(open.shiftId(), 6_000L, StationShift.ShiftEndReason.EXPIRED), "already closed");
        assertEquals(0, repo.findOpen().stream().filter(s -> s.stationId().equals(station)).count(),
            "a closed shift no longer counts as open");
        repo.insert(new StationShift(UUID.randomUUID().toString(), station, "AGUA_Y_JABON", "op", 7_000L, 9_000L, null, null));

        assertThrows(DataIntegrityViolationException.class, () -> repo.insert(new StationShift(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), "AGUA_Y_JABON", "op", 1L, 2L, null, null)), "unknown station (FK)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO station_shifts (shift_id, station_id, protocol_code, started_by, started_at_epoch_ms, ends_at_epoch_ms, open_station_id) VALUES (?, ?, 'AGUA_Y_JABON', 'op', 10, 5, NULL)",
            UUID.randomUUID().toString(), station),
            "ends before it starts");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "UPDATE station_shifts SET end_reason = 'OTRO' WHERE shift_id = ?", open.shiftId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "UPDATE station_shifts SET ended_at_epoch_ms = 8000 WHERE ended_at_epoch_ms IS NULL AND station_id = ?", station),
            "closed shift without a reason or released marker");

        // V10: the kill switch is seeded off, single-row, and photo statuses accept the full lifecycle.
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT capture_enabled FROM evidence_settings WHERE id = 1", Boolean.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO evidence_settings VALUES (2, TRUE, 'x', 1)"));
        String anyAttempt = jdbc.queryForObject("SELECT session_id FROM friction_attempt_validation LIMIT 1", String.class);
        for (String status : new String[]{"DESACTIVADA", "PENDIENTE", "CAPTURADA", "NO_CAPTURADA", "EXPIRADA"}) {
            jdbc.update("UPDATE friction_attempt_validation SET evidence_status = ? WHERE session_id = ?", status, anyAttempt);
        }
    }

    @Test @Order(5) void auditChainSerializesConcurrentAppendsAndDetectsTampering() throws Exception {
        var hasher = new com.handwash.service.audit.AuditChainHasher(null); // same unkeyed mode as the earlier tests of this class
        var repo = new com.handwash.repository.SessionAuditRepository(jdbc, hasher);
        var verifier = new com.handwash.service.audit.AuditChainVerifier(jdbc, hasher);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < 8; t++) {
            int worker = t;
            jobs.add(pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    repo.appendEvent(new com.handwash.model.SessionAuditEvent(0, null, "STEP_CONFIRMED", "SYSTEM", null,
                        "ACCEPTED", null, null, null, null, worker * 1000L + i));
                }
                return null;
            }));
        }
        for (var job : jobs) job.get();
        pool.shutdown();

        var clean = verifier.verify();
        assertTrue(clean.ok(), clean.problems().toString());
        assertTrue(clean.checked() >= 200);

        jdbc.update("UPDATE wash_audit_events SET decision_code = 'REJECTED' WHERE event_id = (SELECT MIN(event_id) FROM wash_audit_events WHERE event_hash IS NOT NULL)");
        assertTrue(verifier.verify().problems().stream().anyMatch(p -> p.code().equals("HASH_MISMATCH")));
    }

    @Test @Order(20) void stationProvisioningAndSingleUseActivationOnPostgres() {
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new com.handwash.service.StationCredentialService(new StationRepository(jdbc), audit);
        var provisioned = stations.provision("Lavamanos PG", "producer-pg", "admin-sub");
        assertNull(stations.authenticate(provisioned.activationToken()));
        var activated = stations.activate(provisioned.activationToken());
        assertNotNull(activated);
        assertEquals(provisioned.station().stationId(), stations.authenticate(activated.credential()).stationId());
        assertNull(stations.activate(provisioned.activationToken()));
    }

    @Test @Order(21) void apiKeysIssueVerifySessionAndRevokeOnPostgres() {
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new com.handwash.service.StationCredentialService(new StationRepository(jdbc), audit);
        var keys = new com.handwash.security.ApiKeyService(new com.handwash.security.ApiKeyRepository(jdbc), stations,
            new com.handwash.repository.StationOperatorAccessRepository(jdbc), audit);
        String stationId = stations.provision("Lavamanos Keys", null, "admin-sub").station().stationId();
        var issued = keys.issue(stationId, "María", java.util.EnumSet.of(com.handwash.security.ApiKeyScope.CONSOLE,
            com.handwash.security.ApiKeyScope.PRODUCER), "admin-sub");
        var login = keys.login(issued.key(), "203.0.113.5");
        assertTrue(login.accepted());
        assertTrue(keys.authenticateSession(login.sessionToken()).accepted());
        assertTrue(keys.revoke(issued.keyId(), "admin-sub"));
        assertFalse(keys.authenticateSession(login.sessionToken()).accepted());
        assertFalse(keys.verify(issued.key(), "203.0.113.5").accepted());
    }
}
