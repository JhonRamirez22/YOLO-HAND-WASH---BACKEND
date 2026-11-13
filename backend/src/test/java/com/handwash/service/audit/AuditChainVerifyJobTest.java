package com.handwash.service.audit;

import com.handwash.model.SessionAuditEvent;
import com.handwash.ops.LogCapture;
import com.handwash.ops.OpsAlert;
import com.handwash.repository.SessionAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditChainVerifyJobTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private SessionAuditRepository repository;
    private AuditChainVerifyJob job;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        var hasher = new AuditChainHasher("j".repeat(48));
        repository = new SessionAuditRepository(jdbc, hasher);
        job = new AuditChainVerifyJob(new AuditChainVerifier(jdbc, hasher),
            new AuditSealService(jdbc, hasher, new UnavailableAuditSealStore()));
        for (int i = 0; i < 5; i++) {
            repository.appendEvent(new SessionAuditEvent(0, "s", "STEP_CONFIRMED", "SYSTEM", null, "ACCEPTED", null, null, null, null, i));
        }
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void anIntactChainRaisesNoAlert() {
        try (var capture = new LogCapture(AuditChainVerifyJob.class)) {
            job.run();
            assertEquals(0, capture.alerts(OpsAlert.Code.AUDIT_CHAIN_BROKEN));
            assertTrue(job.check());
        }
    }

    @Test
    void tamperingRaisesTheChainBrokenAlertEveryRun() {
        jdbc.update("UPDATE wash_audit_events SET decision_code = 'REJECTED' WHERE event_id = (SELECT MIN(event_id) + 2 FROM wash_audit_events)");
        try (var capture = new LogCapture(AuditChainVerifyJob.class)) {
            job.run();
            job.run();
            assertEquals(2, capture.alerts(OpsAlert.Code.AUDIT_CHAIN_BROKEN));
            assertTrue(capture.messages().get(0).contains("HASH_MISMATCH"));
            assertFalse(job.check());
        }
    }
}
