package com.handwash.service.audit;

import com.handwash.model.SessionAuditEvent;
import com.handwash.repository.SessionAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditChainTest {
    private static final String SECRET = "k".repeat(48);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AuditChainHasher hasher;
    private SessionAuditRepository repository;
    private AuditChainVerifier verifier;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        hasher = new AuditChainHasher(SECRET);
        repository = new SessionAuditRepository(jdbc, hasher);
        verifier = new AuditChainVerifier(jdbc, hasher);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private void append(int n) {
        for (int i = 0; i < n; i++) {
            repository.appendEvent(new SessionAuditEvent(0, "s-" + (i % 3), "STEP_CONFIRMED", "SYSTEM", "PASO_1_PALMAS",
                "ACCEPTED", i % 2 == 0 ? null : "R", 1 + i, null, i % 2 == 0 ? 0.9 : null, 1_000L + i));
        }
    }

    private List<String> codes() { return verifier.verify().problems().stream().map(AuditChainVerifier.Problem::code).toList(); }

    @Test
    void anUntouchedChainVerifiesAndMatchesTheHead() {
        append(25);

        var report = verifier.verify();
        assertTrue(report.ok(), report.problems().toString());
        assertTrue(report.keyed());
        assertEquals(25, report.checked());
        assertEquals(jdbc.queryForObject("SELECT MAX(event_id) FROM wash_audit_events", Long.class), report.lastEventId());
        assertEquals(report.headHash(), jdbc.queryForObject("SELECT last_hash FROM audit_chain_head", String.class).trim());
    }

    @Test
    void anEmptyLogIsAValidChain() {
        var report = verifier.verify();
        assertTrue(report.ok());
        assertEquals(0, report.checked());
        assertNull(report.firstChainedEventId());
    }

    @Test
    void editingAnEventIsDetected() {
        append(10);
        jdbc.update("UPDATE wash_audit_events SET decision_code = 'REJECTED' WHERE event_id = (SELECT MIN(event_id) + 4 FROM wash_audit_events)");

        assertTrue(codes().contains("HASH_MISMATCH"));
        assertFalse(verifier.verify().ok());
    }

    @Test
    void deletingAnEventInTheMiddleIsDetected() {
        append(10);
        jdbc.update("DELETE FROM wash_audit_events WHERE event_id = (SELECT MIN(event_id) + 4 FROM wash_audit_events)");

        assertTrue(codes().contains("BROKEN_LINK"));
    }

    @Test
    void insertingAForgedEventIsDetected() {
        append(6);
        jdbc.update("""
            INSERT INTO wash_audit_events (session_id, event_type, decision_code, occurred_at_epoch_ms, prev_hash, event_hash)
            VALUES ('s-x', 'STEP_CONFIRMED', 'ACCEPTED', 9999, ?, ?)""", "a".repeat(64), "b".repeat(64));

        assertFalse(verifier.verify().ok());
    }

    @Test
    void erasingTheHashesOfAnEventIsDetected() {
        append(6);
        jdbc.update("UPDATE wash_audit_events SET event_hash = NULL, prev_hash = NULL WHERE event_id = (SELECT MIN(event_id) + 2 FROM wash_audit_events)");

        assertTrue(codes().contains("UNHASHED_EVENT"));
    }

    @Test
    void truncatingTheTailWithoutTouchingTheHeadIsDetected() {
        append(8);
        jdbc.update("DELETE FROM wash_audit_events WHERE event_id = (SELECT MAX(event_id) FROM wash_audit_events)");

        assertTrue(codes().contains("HEAD_MISMATCH"));
    }

    @Test
    void someoneWithoutTheSecretCannotRewriteAConsistentChain() {
        append(6);
        var forger = new AuditChainHasher(null); // a database administrator who does not know the key
        String previous = AuditChainHasher.GENESIS;
        var rows = jdbc.queryForList("SELECT event_id FROM wash_audit_events ORDER BY event_id", Long.class);
        for (Long id : rows) {
            var event = jdbc.queryForObject("""
                SELECT session_id, event_type, actor_subject, step_code, decision_code, reason_code, attempt_number,
                       frame_sequence, confidence, occurred_at_epoch_ms FROM wash_audit_events WHERE event_id = ?""",
                (rs, n) -> new SessionAuditEvent(id, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getObject(7, Integer.class), rs.getObject(8, Long.class),
                    rs.getObject(9, Double.class), rs.getLong(10)), id);
            String forged = forger.linkHash(previous, event);
            jdbc.update("UPDATE wash_audit_events SET prev_hash = ?, event_hash = ? WHERE event_id = ?", previous, forged, id);
            previous = forged;
        }
        jdbc.update("UPDATE audit_chain_head SET last_hash = ?", previous);

        assertTrue(codes().contains("HASH_MISMATCH"), "the unkeyed rewrite must not verify");
    }

    @Test
    void legacyEventsBeforeTheChainAreOutsideItAndConcurrentWritersStayConsistent() throws Exception {
        jdbc.update("INSERT INTO wash_audit_events (session_id, event_type, decision_code, occurred_at_epoch_ms) VALUES ('old', 'STEP_CONFIRMED', 'ACCEPTED', 1)");
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < 6; t++) {
            int worker = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    repository.appendEvent(new SessionAuditEvent(0, "w-" + worker, "STEP_CONFIRMED", "SYSTEM", null,
                        "ACCEPTED", null, null, null, null, 100L * worker + i));
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) future.get();
        pool.shutdown();

        var report = verifier.verify();
        assertTrue(report.ok(), report.problems().toString());
        assertEquals(60, report.checked());
        assertNotNull(report.firstChainedEventId());
        assertTrue(report.firstChainedEventId() > 1);
    }

    @Test
    void anUnkeyedChainSaysSo() {
        var plainRepository = new SessionAuditRepository(jdbc);
        plainRepository.appendEvent(new SessionAuditEvent(0, "s", "STEP_CONFIRMED", "SYSTEM", null, "ACCEPTED", null, null, null, null, 1));

        var report = new AuditChainVerifier(jdbc, new AuditChainHasher(null)).verify();
        assertTrue(report.ok());
        assertFalse(report.keyed());
    }
}
