package com.handwash.service;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StationOperatorAccessServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private StationCredentialService stations;
    private StationOperatorAccessService operators;
    private String stationId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        operators = new StationOperatorAccessService(new StationOperatorAccessRepository(jdbc), stations, audit);
        var technicalCode = stations.issueEnrollmentCode("Lavamanos A", "admin-subject");
        stationId = stations.enroll(technicalCode.code()).station().stationId();
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void storesOnlyHashAndClaimsOneTimeCodeForItsVerifiedEmailAndStation() {
        var issued = operators.issue(stationId, " Nurse@Hospital.org ", "admin-subject");

        assertNotEquals(issued.code(), jdbc.queryForObject(
            "SELECT code_hash FROM station_operator_access_codes WHERE code_id = ?", String.class, issued.codeId()));
        assertEquals(StationCredentialService.sha256(issued.code()), jdbc.queryForObject(
            "SELECT code_hash FROM station_operator_access_codes WHERE code_id = ?", String.class, issued.codeId()));
        assertEquals(StationOperatorAccessService.ClaimOutcome.EMAIL_MISMATCH,
            operators.claim(issued.code(), "cognito-subject", "other@hospital.org").outcome());
        assertNull(jdbc.queryForObject(
            "SELECT consumed_at_epoch_ms FROM station_operator_access_codes WHERE code_id = ?", Long.class, issued.codeId()));

        var result = operators.claim(issued.code(), "cognito-subject", "nurse@hospital.org");

        assertEquals(StationOperatorAccessService.ClaimOutcome.CLAIMED, result.outcome());
        assertEquals(stationId, result.grant().stationId());
        assertEquals("nurse@hospital.org", result.grant().email());
        assertEquals(StationOperatorAccessService.ClaimOutcome.INVALID_CODE,
            operators.claim(issued.code(), "another-subject", "nurse@hospital.org").outcome());
        assertEquals("cognito-subject", jdbc.queryForObject(
            "SELECT consumed_by FROM station_operator_access_codes WHERE code_id = ?", String.class, issued.codeId()));
        assertNotNull(operators.activeGrant("cognito-subject"));
        assertEquals(2, new SessionAuditRepository(jdbc).findAdminAccessEvents(100).stream()
            .filter(event -> "OPERATOR_ACCESS_CODE_ISSUED".equals(event.eventType())
                || "OPERATOR_ACCESS_CODE_CLAIMED".equals(event.eventType())).count());
    }

    @Test
    void revokedCodeCannotBeClaimedAndRevokedGrantRequiresANewCode() {
        var revokedCode = operators.issue(stationId, "nurse@hospital.org", "admin-subject");
        assertEquals(true, operators.revokeCode(revokedCode.codeId(), "admin-subject"));
        assertEquals(StationOperatorAccessService.ClaimOutcome.INVALID_CODE,
            operators.claim(revokedCode.code(), "cognito-subject", "nurse@hospital.org").outcome());

        var first = operators.issue(stationId, "nurse@hospital.org", "admin-subject");
        operators.claim(first.code(), "cognito-subject", "nurse@hospital.org");
        assertEquals(stationId, operators.revokeGrant("cognito-subject", "admin-subject"));
        assertNull(operators.activeGrant("cognito-subject"));

        var replacement = operators.issue(stationId, "nurse@hospital.org", "admin-subject");
        assertEquals(StationOperatorAccessService.ClaimOutcome.CLAIMED,
            operators.claim(replacement.code(), "cognito-subject", "nurse@hospital.org").outcome());
    }

    @Test
    void rejectsInvalidStationAndEmailAndDoesNotGrantSecondStation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> operators.issue("not-a-uuid", "nurse@hospital.org", "admin-subject"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> operators.issue(stationId, "invalid", "admin-subject"));

        var issued = operators.issue(stationId, "nurse@hospital.org", "admin-subject");
        operators.claim(issued.code(), "cognito-subject", "nurse@hospital.org");
        var secondStationCode = stations.issueEnrollmentCode("Lavamanos B", "admin-subject");
        String secondStation = stations.enroll(secondStationCode.code()).station().stationId();
        var secondAssignment = operators.issue(secondStation, "nurse@hospital.org", "admin-subject");
        assertEquals(StationOperatorAccessService.ClaimOutcome.ALREADY_ASSIGNED,
            operators.claim(secondAssignment.code(), "cognito-subject", "nurse@hospital.org").outcome());
        assertFalse(jdbc.queryForObject("SELECT COUNT(*) FROM station_operator_grants WHERE station_id = ?",
            Integer.class, secondStation) > 0);
    }
}
