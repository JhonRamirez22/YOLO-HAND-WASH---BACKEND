package com.handwash.repository;

import tools.jackson.databind.ObjectMapper;
import com.handwash.model.Violation;
import com.handwash.model.HandwashingAttemptSummary;
import com.handwash.model.ViolationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedAttemptRepositoryTest {
    private EmbeddedDatabase database;
    private FailedAttemptRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql")
            .build();
        repository = new FailedAttemptRepository(new JdbcTemplate(database), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.shutdown();
    }

    @Test
    void roundTripsOnlyTheFailedAttemptSummary() {
        HandwashingAttemptSummary attempt = sampleAttempt(1);

        repository.insertIfAbsent("session-a", attempt);

        List<HandwashingAttemptSummary> stored = repository.findBySession("session-a");
        assertEquals(1, stored.size());
        assertEquals(attempt.numero(), stored.get(0).numero());
        assertEquals(attempt.motivoReinicio(), stored.get(0).motivoReinicio());
        assertEquals(attempt.tiempoPorPasoMs(), stored.get(0).tiempoPorPasoMs());
        assertEquals(attempt.infracciones().get(0).getDetalle(), stored.get(0).infracciones().get(0).getDetalle());
        assertTrue(repository.findBySession("session-b").isEmpty());
    }

    @Test
    void duplicateAttemptInsertIsIdempotent() {
        HandwashingAttemptSummary attempt = sampleAttempt(3);

        repository.insertIfAbsent("session-a", attempt);
        repository.insertIfAbsent("session-a", attempt);

        List<HandwashingAttemptSummary> stored = repository.findBySession("session-a");
        assertEquals(1, stored.size());
        assertEquals(attempt.numero(), stored.get(0).numero());
    }

    @Test
    void hostileSessionIdentifierIsOnlyAValueAndCannotReadOrDeleteOtherRows() {
        repository.insertIfAbsent("session-a", sampleAttempt(1));
        String hostileId = "session-a' OR '1'='1";

        assertTrue(repository.findBySession(hostileId).isEmpty());
        assertEquals(0, repository.deleteBySession(hostileId));
        assertEquals(1, repository.findBySession("session-a").size());
    }

    @Test
    void supportsExplicitSessionDeletionAndRetentionCleanup() {
        repository.insertIfAbsent("session-a", sampleAttempt(1));
        repository.insertIfAbsent("session-b", sampleAttempt(1));

        assertEquals(1, repository.deleteBySession("session-a"));
        assertEquals(0, repository.deleteBySession("session-a"));
        assertEquals(1, repository.deleteCreatedBefore(System.currentTimeMillis() + 1_000L, Set.of()));
        assertTrue(repository.findBySession("session-a").isEmpty());
        assertTrue(repository.findBySession("session-b").isEmpty());
    }

    @Test
    void retentionCleanupPreservesRowsForSessionsStillRegisteredInMemory() {
        repository.insertIfAbsent("active-session", sampleAttempt(1));
        repository.insertIfAbsent("orphan-session", sampleAttempt(1));

        int removed = repository.deleteCreatedBefore(
            System.currentTimeMillis() + 1_000L, Set.of("active-session"));

        assertEquals(1, removed);
        assertEquals(1, repository.findBySession("active-session").size());
        assertTrue(repository.findBySession("orphan-session").isEmpty());
    }

    @Test
    void retentionCleanupRequiresAnExplicitSessionProtectionSet() {
        repository.insertIfAbsent("active-session", sampleAttempt(1));

        assertThrows(NullPointerException.class,
            () -> repository.deleteCreatedBefore(System.currentTimeMillis() + 1_000L, null));
        assertEquals(1, repository.findBySession("active-session").size());
    }

    @Test
    void retainsAtMostOneHundredSummariesPerSession() {
        for (int number = 1; number <= 101; number++) {
            repository.insertIfAbsent("session-a", sampleAttempt(number));
        }

        List<HandwashingAttemptSummary> attempts = repository.findBySession("session-a");
        assertEquals(100, attempts.size());
        assertEquals(2, attempts.get(0).numero());
        assertEquals(101, attempts.get(99).numero());
    }

    private HandwashingAttemptSummary sampleAttempt(int number) {
        Violation infraction = new Violation(
            ViolationType.PASO_OMITIDO, "Paso omitido", "PASO_3_INTERDIGITALES", "2026-09-25T12:00:00Z");
        return new HandwashingAttemptSummary(
            number,
            "REINICIADO",
            "PASO_FUERA_DE_SECUENCIA",
            4_200L,
            Map.of("PASO_1_PALMAS", 1_200L),
            List.of(infraction));
    }
}
