package com.handwash.repository;

import com.handwash.model.EncounterAuditRecord;
import com.handwash.model.SessionAuditEvent;
import com.handwash.service.SessionAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuditRepositoryTest {
    private EmbeddedDatabase database;
    private SessionAuditRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        repository = new SessionAuditRepository(new JdbcTemplate(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsMetadataAndPaginatesAuditEventsWithoutMediaFields() {
        String sessionId = UUID.randomUUID().toString();
        repository.createEncounter(new EncounterAuditRecord(sessionId, "DOMESTICO", "ACTIVE",
            1_000, null, "model-v1", "a".repeat(64), "dataset-v1", 0));
        repository.appendEvent(new SessionAuditEvent(0, sessionId, "STEP_CONFIRMED", "SYSTEM",
            "Paso1_Palmas", "CONFIRMED", null, 1, 11L, 0.91, 1_100));
        repository.appendEvent(new SessionAuditEvent(0, sessionId, "ATTEMPT_RESTARTED", "SYSTEM",
            null, "RESTARTED", "PASO_OMITIDO", 2, 22L, 0.42, 1_200));
        repository.appendEvent(new SessionAuditEvent(0, null, "ADMIN_ENCOUNTERS_LISTED", "admin-sub",
            null, "READ", null, null, null, null, 1_300));

        assertTrue(repository.encounterExists(sessionId));
        assertEquals("model-v1", repository.findRecentEncounters(1).get(0).modelVersion());
        var latest = repository.findEvents(sessionId, null, 1).get(0);
        assertEquals("ATTEMPT_RESTARTED", latest.eventType());
        var older = repository.findEvents(sessionId, latest.eventId(), 10).get(0);
        assertEquals("STEP_CONFIRMED", older.eventType());
        assertEquals(latest.eventId() - 1, older.eventId());
        assertEquals(1, repository.findAdminAccessEvents(10).size());
    }

    @Test
    void updateEncounterReportsMissingSessionRatherThanSilentlyLosingAuditState() {
        assertEquals(0, repository.updateEncounter(UUID.randomUUID().toString(), "DELETED", 10L, 0));
    }

    @Test
    void runtimeRestartInterruptsOnlyNonTerminalEncounters() {
        String active = UUID.randomUUID().toString();
        String inProgress = UUID.randomUUID().toString();
        String completed = UUID.randomUUID().toString();
        repository.createEncounter(new EncounterAuditRecord(active, "DOMESTICO", "ACTIVE",
            1_000, null, "model-v1", "a".repeat(64), "dataset-v1", 0));
        repository.createEncounter(new EncounterAuditRecord(inProgress, "DOMESTICO", "IN_PROGRESS",
            1_100, null, "model-v1", "a".repeat(64), "dataset-v1", 0));
        repository.createEncounter(new EncounterAuditRecord(completed, "DOMESTICO", "COMPLETED",
            1_200, 1_500L, "model-v1", "a".repeat(64), "dataset-v1", 0));

        assertEquals(2, repository.findNonTerminalEncounterIds().size());
        SessionAuditService service = new SessionAuditService(repository);
        assertEquals(2, service.interruptNonTerminalEncountersAfterRestart());
        assertEquals(0, service.interruptNonTerminalEncountersAfterRestart());
        assertEquals("INTERRUPTED", jdbcStatus(active));
        assertEquals("INTERRUPTED", jdbcStatus(inProgress));
        assertEquals("COMPLETED", jdbcStatus(completed));
        assertEquals(0, repository.findNonTerminalEncounterIds().size());
        assertEquals(1L, repository.findEvents(active, null, 10).stream()
            .filter(event -> "SESSION_INTERRUPTED".equals(event.eventType())
                && "BACKEND_RESTART".equals(event.reasonCode())).count());
    }

    private String jdbcStatus(String sessionId) {
        return new JdbcTemplate(database).queryForObject(
            "SELECT status FROM wash_encounters WHERE session_id = ?", String.class, sessionId);
    }
}
