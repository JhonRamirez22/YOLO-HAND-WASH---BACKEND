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

import java.util.concurrent.TimeUnit;

import static com.handwash.service.evidence.EvidenceTestSupport.jpeg;
import static com.handwash.service.evidence.EvidenceUploadService.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EvidenceUploadServiceTest {
    private static final String SESSION = "11111111-1111-1111-1111-111111111111";
    private static final long NOW = 10_000_000L;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private WashEvidenceRepository repository;
    private InMemoryStore store;
    private SessionAuditService audit;
    private EvidenceSettingsService settings;
    private EvidenceUploadService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        repository = new WashEvidenceRepository(jdbc);
        store = new InMemoryStore();
        audit = mock(SessionAuditService.class);
        var properties = new EvidenceProperties(true, "bucket", "kms-key", 30, "sa-east-1");
        settings = new EvidenceSettingsService(properties, new EvidenceSettingsRepository(jdbc), audit, () -> NOW);
        settings.setEnabled(true, "admin-sub");
        service = new EvidenceUploadService(settings, properties, repository, store, audit, () -> NOW);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private void attempt(int number, String outcome, boolean terminal, Long finishedAt, String evidenceStatus) {
        jdbc.update("""
            INSERT INTO friction_attempt_validation (session_id, attempt_number, policy_version, revision, status,
                terminal, created_at_epoch_ms, updated_at_epoch_ms, finished_at_epoch_ms, outcome, evidence_status)
            VALUES (?, ?, 'BALANCED_FRICTION_V2', 1, 'DONE', ?, 1, 1, ?, ?, ?)""",
            SESSION, number, terminal, finishedAt, outcome, evidenceStatus);
    }

    @Test
    void storesTheReEncodedPhotoOnceForAFreshReport() {
        attempt(1, "REPORTADO", true, NOW - 10_000, "PENDIENTE");

        assertEquals(Outcome.STORED, service.upload(SESSION, 1, jpeg(800, 600)));

        assertEquals(1, store.objects.size());
        String key = store.objects.keySet().iterator().next();
        assertTrue(key.startsWith("reports/"), key);
        assertEquals(key, repository.findObjectKey(SESSION, 1).orElseThrow());
        assertEquals("CAPTURADA", repository.findAttempt(SESSION, 1).orElseThrow().evidenceStatus());
        long expires = jdbc.queryForObject("SELECT expires_at_epoch_ms FROM wash_report_evidence", Long.class);
        assertEquals(NOW + TimeUnit.DAYS.toMillis(30), expires);
        verify(audit).recordStationEvent(SESSION, "STATION", "EVIDENCE_PHOTO_STORED", "STORED", "ATTEMPT_1");
        assertEquals(Outcome.ALREADY_STORED, service.upload(SESSION, 1, jpeg(800, 600)));
        assertEquals(1, store.objects.size(), "a second upload must not create a second object");
    }

    @Test
    void refusesEverythingWhileTheKillSwitchIsOff() {
        attempt(1, "REPORTADO", true, NOW - 10_000, "PENDIENTE");
        settings.setEnabled(false, "admin-sub");

        assertEquals(Outcome.DISABLED, service.upload(SESSION, 1, jpeg(800, 600)));
        assertTrue(store.objects.isEmpty());
    }

    @Test
    void onlyAFinishedReportWaitingForItsPhotoIsEligible() {
        attempt(1, "CORRECTO", true, NOW - 10_000, null);
        attempt(2, "REPORTADO", false, NOW - 10_000, "PENDIENTE");
        attempt(3, "REPORTADO", true, NOW - 10_000, "DESACTIVADA");
        attempt(4, "REPORTADO", true, NOW - EvidenceProperties.UPLOAD_WINDOW_MS - 1, "PENDIENTE");
        attempt(5, "INTERRUMPIDO", true, NOW - 10_000, "PENDIENTE");

        for (int number = 1; number <= 5; number++) {
            assertEquals(Outcome.NOT_ELIGIBLE, service.upload(SESSION, number, jpeg(800, 600)), "attempt " + number);
        }
        assertEquals(Outcome.NOT_ELIGIBLE, service.upload(SESSION, 99, jpeg(800, 600)), "unknown attempt");
        assertTrue(store.objects.isEmpty());
        verifyNoInteractionsWithEvidenceAudit();
    }

    private void verifyNoInteractionsWithEvidenceAudit() {
        verify(audit, org.mockito.Mockito.never())
            .recordStationEvent(SESSION, "STATION", "EVIDENCE_PHOTO_STORED", "STORED", "ATTEMPT_1");
    }

    @Test
    void rejectsNonImagesWithoutTouchingTheStore() {
        attempt(1, "REPORTADO", true, NOW - 10_000, "PENDIENTE");

        assertEquals(Outcome.INVALID_IMAGE, service.upload(SESSION, 1, new byte[4_000]));

        assertTrue(store.objects.isEmpty());
        assertEquals("PENDIENTE", repository.findAttempt(SESSION, 1).orElseThrow().evidenceStatus());
    }

    @Test
    void storeFailureLeavesTheAttemptPendingAndReportsUnavailable() {
        attempt(1, "REPORTADO", true, NOW - 10_000, "PENDIENTE");
        store.failPut = true;

        assertEquals(Outcome.UNAVAILABLE, service.upload(SESSION, 1, jpeg(800, 600)));

        assertEquals("PENDIENTE", repository.findAttempt(SESSION, 1).orElseThrow().evidenceStatus());
        assertTrue(repository.findObjectKey(SESSION, 1).isEmpty());
    }

    @Test
    void databaseFailureDeletesTheObjectItJustWrote() {
        attempt(1, "REPORTADO", true, NOW - 10_000, "PENDIENTE");
        jdbc.execute("DROP TABLE wash_report_evidence");

        assertEquals(Outcome.UNAVAILABLE, service.upload(SESSION, 1, jpeg(800, 600)));

        assertTrue(store.objects.isEmpty(), "an unreferenced photo would never be cleaned up");
        assertFalse(store.objects.containsKey("x"));
    }
}
