package com.handwash.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailedAttemptRepositoryTest {
    private EmbeddedDatabase database;
    private FailedAttemptRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        repository = new FailedAttemptRepository(new JdbcTemplate(database), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void returnsNewestSanitizedMetadataWithoutStoredPayload() {
        String sessionId = UUID.randomUUID().toString();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        jdbc.update("""
            INSERT INTO failed_attempts
                (session_id, attempt_number, result, reason, duration_ms, payload_json, created_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, sessionId, 1, "REINICIADO", "CONTACTO_RIESGO", 1500, "{\"private\":\"hidden\"}", 100);
        jdbc.update("""
            INSERT INTO failed_attempts
                (session_id, attempt_number, result, reason, duration_ms, payload_json, created_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), 1, "unexpected", "operator name", 900, "{}", 200);

        var results = repository.findRecentAuditRecords(1);

        assertEquals(1, results.size());
        assertEquals("UNKNOWN", results.get(0).result());
        assertEquals("OTHER", results.get(0).reasonCode());
        assertEquals(900, results.get(0).durationMs());
    }

    @Test
    void enforcesAdminPageLimit() {
        assertThrows(IllegalArgumentException.class, () -> repository.findRecentAuditRecords(101));
    }
}
