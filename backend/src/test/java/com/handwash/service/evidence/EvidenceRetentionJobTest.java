package com.handwash.service.evidence;

import com.handwash.service.SessionAuditService;
import com.handwash.service.evidence.EvidenceTestSupport.InMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvidenceRetentionJobTest {
    private static final String SESSION = "22222222-2222-2222-2222-222222222222";
    private static final long NOW = 100_000_000L;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private WashEvidenceRepository repository;
    private InMemoryStore store;
    private SessionAuditService audit;
    private EvidenceRetentionJob job;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        repository = new WashEvidenceRepository(jdbc);
        store = new InMemoryStore();
        audit = mock(SessionAuditService.class);
        job = new EvidenceRetentionJob(repository, store, audit, () -> NOW);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private void attempt(int number, Long finishedAt, String evidenceStatus) {
        jdbc.update("""
            INSERT INTO friction_attempt_validation (session_id, attempt_number, policy_version, revision, status,
                terminal, created_at_epoch_ms, updated_at_epoch_ms, finished_at_epoch_ms, outcome, evidence_status)
            VALUES (?, ?, 'BALANCED_FRICTION_V2', 1, 'DONE', TRUE, 1, 1, ?, 'REPORTADO', ?)""",
            SESSION, number, finishedAt, evidenceStatus);
    }

    private void photo(int number, String key, long capturedAt, long expiresAt) {
        store.objects.put(key, new byte[]{1});
        repository.store(SESSION, number, key, "a".repeat(64), capturedAt, expiresAt);
    }

    private String status(int number) {
        return repository.findAttempt(SESSION, number).orElseThrow().evidenceStatus();
    }

    @Test
    void deletesOnlyExpiredPhotosAndRemembersThemAsExpired() {
        attempt(1, 1L, "PENDIENTE");
        attempt(2, 1L, "PENDIENTE");
        photo(1, "reports/old.jpg", 10, NOW - 1);
        photo(2, "reports/fresh.jpg", 10, NOW + 1_000);

        job.run();

        assertEquals("EXPIRADA", status(1));
        assertTrue(repository.findObjectKey(SESSION, 1).isEmpty());
        assertTrue(!store.objects.containsKey("reports/old.jpg"));
        assertEquals("CAPTURADA", status(2));
        assertTrue(store.objects.containsKey("reports/fresh.jpg"));
        verify(audit).recordStationEvent(SESSION, "SYSTEM", "EVIDENCE_PHOTO_EXPIRED", "EXPIRED", "ATTEMPT_1");
    }

    @Test
    void keepsTheRowWhenTheObjectCannotBeDeletedSoItIsRetried() {
        attempt(1, 1L, "PENDIENTE");
        photo(1, "reports/old.jpg", 10, NOW - 1);
        store.failDelete = true;

        job.run();

        assertEquals("CAPTURADA", status(1));
        assertTrue(repository.findObjectKey(SESSION, 1).isPresent());
        store.failDelete = false;
        job.run();
        assertEquals("EXPIRADA", status(1));
    }

    @Test
    void closesUploadsWhoseWindowHasPassed() {
        attempt(1, NOW - EvidenceProperties.UPLOAD_WINDOW_MS - 1, "PENDIENTE");
        attempt(2, NOW - 1_000, "PENDIENTE");

        job.run();

        assertEquals("NO_CAPTURADA", status(1));
        assertEquals("PENDIENTE", status(2));
    }
}
