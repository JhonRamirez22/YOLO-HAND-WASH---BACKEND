package com.handwash.service;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationCredentialServiceTest {
    private EmbeddedDatabase database;
    private StationCredentialService stations;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        stations = new StationCredentialService(new StationRepository(jdbc),
            new SessionAuditService(new SessionAuditRepository(jdbc)));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void enrollsOnceStoresOnlyHashesAndRevocationDisablesTheCredential() {
        StationCredentialService.IssuedEnrollmentCode issued = stations.issueEnrollmentCode(
            "  Lavamanos A  ", "admin-subject");

        StationCredentialService.EnrolledStation enrolled = stations.enroll(issued.code());

        assertNotNull(enrolled);
        assertEquals("Lavamanos A", enrolled.station().stationName());
        assertNotEquals(issued.code(), enrolled.credential());
        assertNotNull(stations.authenticate(enrolled.credential()));
        assertNull(stations.enroll(issued.code()));

        JdbcTemplate jdbc = new JdbcTemplate(database);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM station_enrollment_codes WHERE code_hash = ?",
            Integer.class, issued.code()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM station_enrollment_codes WHERE code_hash = ?",
            Integer.class, StationCredentialService.sha256(issued.code())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM handwash_stations WHERE credential_hash = ?",
            Integer.class, enrolled.credential()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM handwash_stations WHERE credential_hash = ?",
            Integer.class, StationCredentialService.sha256(enrolled.credential())));

        assertTrue(stations.revoke(enrolled.station().stationId(), "admin-subject"));
        assertNull(stations.authenticate(enrolled.credential()));
    }

    @Test
    void rejectsMalformedOrReusedEnrollmentCodesAndInvalidNames() {
        assertNull(stations.enroll("bad"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> stations.issueEnrollmentCode("\n", "admin-subject"));
        assertNull(stations.enroll("x".repeat(40)));
    }

    @Test
    void bindsProducerClientAndIncludesTheBindingInAdminAudit() {
        StationCredentialService.IssuedEnrollmentCode issued = stations.issueEnrollmentCode(
            "Lavamanos RTX", "admin-subject");
        StationCredentialService.EnrolledStation enrolled = stations.enroll(issued.code());
        String producerClientId = "42210eq66kdtslmk1jsnnhlvkm";

        stations.bindProducerClient(enrolled.station().stationId(), producerClientId, "admin-subject");

        JdbcTemplate jdbc = new JdbcTemplate(database);
        assertEquals(producerClientId, jdbc.queryForObject(
            "SELECT producer_client_id FROM handwash_stations WHERE station_id = ?",
            String.class, enrolled.station().stationId()));
        var bindingEvent = new SessionAuditRepository(jdbc).findAdminAccessEvents(20).stream()
            .filter(event -> "STATION_PRODUCER_BOUND".equals(event.eventType())
                && enrolled.station().stationId().equals(event.reasonCode()))
            .findFirst().orElseThrow();
        assertEquals("admin-subject", bindingEvent.actorSubject());
        assertEquals("BOUND", bindingEvent.decisionCode());
    }

    @Test
    void cloudProfileCanInstantiateServiceWithItsAutowiredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("cloud");
            JdbcTemplate jdbc = new JdbcTemplate(database);
            context.getBeanFactory().registerSingleton("stationRepository", new StationRepository(jdbc));
            context.getBeanFactory().registerSingleton("sessionAuditService",
                new SessionAuditService(new SessionAuditRepository(jdbc)));
            context.register(StationCredentialService.class);

            context.refresh();

            assertNotNull(context.getBean(StationCredentialService.class));
        }
    }
}
