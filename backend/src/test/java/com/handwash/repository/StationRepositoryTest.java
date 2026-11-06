package com.handwash.repository;

import com.handwash.model.StationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StationRepositoryTest {
    private EmbeddedDatabase database;
    private StationRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        repository = new StationRepository(new JdbcTemplate(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void enrollmentCodeIsConsumedOnceAndExpiresStrictly() {
        repository.createEnrollmentCode("a".repeat(64), "Lavamanos 1", "admin", 100, 200);
        assertNotNull(repository.consumeEnrollmentCode("a".repeat(64), 150));
        assertNull(repository.consumeEnrollmentCode("a".repeat(64), 150));

        repository.createEnrollmentCode("b".repeat(64), "Lavamanos 2", "admin", 100, 200);
        assertNull(repository.consumeEnrollmentCode("b".repeat(64), 200));
    }

    @Test
    void stationCredentialHashIsUniqueAndRevocationDisablesAuthentication() {
        String id = UUID.randomUUID().toString();
        repository.createStation(new StationRecord(id, "Puesto A", 1, "admin",
            null, null, null, null), "c".repeat(64));

        assertEquals(id, repository.findActiveByCredentialHash("c".repeat(64)).stationId());
        assertEquals(1, repository.revoke(id, 10));
        assertNull(repository.findActiveByCredentialHash("c".repeat(64)));
        assertEquals(10L, repository.findAll().get(0).revokedAtEpochMs());
    }

    @Test
    void aStationHoldsOnlyOneActiveSessionAndReleasesItsReservation() {
        String id = UUID.randomUUID().toString();
        String firstSession = UUID.randomUUID().toString();
        String secondSession = UUID.randomUUID().toString();
        repository.createStation(new StationRecord(id, "Puesto A", 1, "admin",
            null, null, null, null), "d".repeat(64));

        assertEquals(1, repository.reserveSession(id, firstSession, UUID.randomUUID().toString(), 5));
        assertEquals(0, repository.reserveSession(id, secondSession, UUID.randomUUID().toString(), 6));
        assertEquals(firstSession, repository.findActiveById(id).activeSessionId());
        assertEquals(1, repository.releaseSession(firstSession));
        assertEquals(1, repository.reserveSession(id, secondSession, UUID.randomUUID().toString(), 7));
    }

    @Test
    void producerClientBindingIsPersistedAndCannotBeAddedToRevokedStations() {
        String id = UUID.randomUUID().toString();
        repository.createStation(new StationRecord(id, "Puesto RTX", 1, "admin",
            null, null, null, null), "e".repeat(64));

        assertEquals(1, repository.bindProducerClient(id, "m2m_client_123"));
        assertEquals("m2m_client_123", repository.findActiveById(id).producerClientId());
        repository.revoke(id, 10);
        assertEquals(0, repository.bindProducerClient(id, "other_client"));
    }
}
