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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSealServiceTest {
    private static final String SECRET = "s".repeat(48);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AuditChainHasher hasher;
    private SessionAuditRepository repository;
    private AuditChainVerifier verifier;
    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private boolean failExport;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private AuditSealService seals;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        hasher = new AuditChainHasher(SECRET);
        repository = new SessionAuditRepository(jdbc, hasher);
        verifier = new AuditChainVerifier(jdbc, hasher);
        AuditSealStore store = new AuditSealStore() {
            @Override public void put(String key, byte[] bytes) {
                if (failExport) throw new IllegalStateException("bucket down");
                objects.put(key, bytes);
            }
            @Override public boolean durable() { return true; }
        };
        seals = new AuditSealService(jdbc, hasher, store, clock::get);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private void event(String type) {
        repository.appendEvent(new SessionAuditEvent(0, "s", type, "SYSTEM", null, "ACCEPTED", null, null, null, null, clock.get()));
    }

    private void finishedWash(int attempt, String outcome) {
        jdbc.update("""
            INSERT INTO friction_attempt_validation (session_id, attempt_number, policy_version, revision, status, terminal,
                created_at_epoch_ms, updated_at_epoch_ms, finished_at_epoch_ms, started_at_epoch_ms, duration_ms, result, outcome)
            VALUES ('11111111-1111-1111-1111-111111111111', ?, 'V2', 1, 'DONE', TRUE, 1, ?, ?, 1, 40000, 'FRICTION_SEQUENCE_COMPLETE', ?)""",
            attempt, clock.get(), clock.get(), outcome);
    }

    @Test
    void sealingStoresASignedCopyOutsideTheDatabaseAndTheVerifierMatchesIt() {
        event("A"); event("B"); finishedWash(1, "CORRECTO");
        clock.addAndGet(1_000);

        var seal = seals.sealIfNeeded().orElseThrow();

        assertEquals(2, seal.eventCount());
        assertEquals(1, seal.resultsCount());
        assertTrue(seal.keyed());
        assertEquals(1, objects.size());
        String document = new String(objects.values().iterator().next(), StandardCharsets.UTF_8);
        assertTrue(document.contains(seal.headHash()));
        assertTrue(document.contains(seal.signature()));
        assertEquals(seal.objectKey(), objects.keySet().iterator().next());
        assertTrue(seals.resultsStillMatch(seal));
        assertTrue(verifier.verify().matchesLatestSeal());
    }

    @Test
    void nothingNewMeansNoNewSeal() {
        event("A");
        clock.addAndGet(1_000);
        assertTrue(seals.sealIfNeeded().isPresent());
        clock.addAndGet(1_000);
        assertTrue(seals.sealIfNeeded().isEmpty());
        assertEquals(1, seals.recent(10).size());

        event("B");
        clock.addAndGet(1_000);
        assertTrue(seals.sealIfNeeded().isPresent());
    }

    @Test
    void rewritingAFinishedWashAfterItWasSealedIsDetected() {
        event("A"); finishedWash(1, "CORRECTO");
        clock.addAndGet(1_000);
        var seal = seals.sealIfNeeded().orElseThrow();

        jdbc.update("UPDATE friction_attempt_validation SET outcome = 'REPORTADO'");

        assertFalse(seals.resultsStillMatch(seal));
    }

    @Test
    void truncatingTheTailAndTheHeadTogetherIsCaughtByTheSeal() {
        event("A"); event("B"); event("C");
        clock.addAndGet(1_000);
        seals.sealIfNeeded().orElseThrow();

        // The attacker removes the last event and rewinds the head so the chain alone looks consistent.
        Long lastId = jdbc.queryForObject("SELECT MAX(event_id) FROM wash_audit_events", Long.class);
        String previous = jdbc.queryForObject("SELECT prev_hash FROM wash_audit_events WHERE event_id = ?", String.class, lastId).trim();
        jdbc.update("DELETE FROM wash_audit_events WHERE event_id = ?", lastId);
        Long newLast = jdbc.queryForObject("SELECT MAX(event_id) FROM wash_audit_events", Long.class);
        jdbc.update("UPDATE audit_chain_head SET last_event_id = ?, last_hash = ?", newLast, previous);

        var report = verifier.verify();
        assertFalse(report.matchesLatestSeal());
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.code().equals("SEAL_MISMATCH")));
    }

    @Test
    void aFailedExportIsNotRecordedAsASealAndIsRetried() {
        event("A");
        clock.addAndGet(1_000);
        failExport = true;
        assertThrows(IllegalStateException.class, () -> seals.sealIfNeeded());
        assertTrue(seals.recent(5).isEmpty());

        failExport = false;
        assertTrue(seals.sealIfNeeded().isPresent());
        assertEquals(1, objects.size());
    }

    @Test
    void withoutADurableStoreTheSealStaysInTheDatabaseAndSaysSo() {
        var local = new AuditSealService(jdbc, hasher, new UnavailableAuditSealStore(), clock::get);
        event("A");
        clock.addAndGet(1_000);

        var seal = local.sealIfNeeded().orElseThrow();

        assertEquals(null, seal.objectKey());
        assertTrue(objects.isEmpty());
    }
}
