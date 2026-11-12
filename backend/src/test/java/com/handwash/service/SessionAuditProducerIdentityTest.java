package com.handwash.service;

import com.handwash.model.EncounterAuditRecord;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.api.v1.dto.ProducerBuildIdentityRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionAuditProducerIdentityTest {
    private EmbeddedDatabase database;
    private SessionAuditRepository repository;
    private SessionAuditService audit;
    private final String modelHash = "a".repeat(64);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        repository = new SessionAuditRepository(new JdbcTemplate(database));
        audit = new SessionAuditService(repository);
        ReflectionTestUtils.setField(audit, "modelVersion", "yolo-26n-r3");
        ReflectionTestUtils.setField(audit, "modelSha256", modelHash);
        ReflectionTestUtils.setField(audit, "datasetVersion", "friction-v4");
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void releaseMismatchIsRejectedBeforeRegisteringProducer() {
        assertThrows(IllegalArgumentException.class, () -> audit.validateProducerBuild(
            new ProducerBuildIdentityRequest("other", modelHash, "friction-v4")));
        assertThrows(IllegalArgumentException.class, () -> audit.validateProducerBuild(
            new ProducerBuildIdentityRequest("yolo-26n-r3", "not-a-hash", "friction-v4")));
    }

    @Test
    void matchingBuildBindsCognitoClientAndEpochToEncounterAudit() {
        String sessionId = UUID.randomUUID().toString();
        repository.createEncounter(new EncounterAuditRecord(sessionId, "DOMESTICO", "ACTIVE",
            1_000L, null, "yolo-26n-r3", modelHash, "friction-v4", 0));
        audit.validateProducerBuild(new ProducerBuildIdentityRequest(
            "yolo-26n-r3", modelHash.toUpperCase(), "friction-v4"));

        audit.recordProducerEpoch(sessionId, "registered-rtx-client", "epoch-123", false);

        var encounter = repository.findRecentEncounters(1).get(0);
        assertEquals("registered-rtx-client", encounter.producerClientId());
        assertEquals("epoch-123", encounter.producerEpoch());
        var event = repository.findEvents(sessionId, null, 1).get(0);
        assertEquals("PRODUCER_EPOCH_REGISTERED", event.eventType());
        assertEquals("registered-rtx-client", event.actorSubject());
        assertEquals("BUILD_IDENTITY_MATCHED", event.reasonCode());
    }
}
