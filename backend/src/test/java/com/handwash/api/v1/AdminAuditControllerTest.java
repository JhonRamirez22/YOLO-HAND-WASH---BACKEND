package com.handwash.api.v1;

import com.handwash.model.EncounterAuditRecord;
import com.handwash.service.SessionAuditService;
import com.handwash.repository.FailedAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditControllerTest {
    private static final String SUBJECT = "cognito-admin-subject";
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final FailedAttemptRepository failedAttempts = mock(FailedAttemptRepository.class);
    private final AdminAuditController controller = new AdminAuditController(audit, failedAttempts);
    private final Jwt admin = Jwt.withTokenValue("opaque-test")
        .header("alg", "none")
        .issuer("https://cognito-idp.sa-east-1.amazonaws.com/sa-east-1_example")
        .subject(SUBJECT)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();

    @Test
    void recordsAdminAccessBeforeReturningEncounterMetadata() {
        when(audit.recentEncounters(20)).thenReturn(List.of(new EncounterAuditRecord(
            UUID.randomUUID().toString(), "DOMESTICO", "ACTIVE", 1, null,
            "model-v1", "a".repeat(64), "dataset-v1", 0)));

        var response = controller.encounters(admin, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(audit).recordAdminAccess(SUBJECT, "ADMIN_ENCOUNTERS_LISTED", null);
    }

    @Test
    void rejectsInvalidPaginationBeforeAccessingStorage() {
        var response = controller.encounters(admin, 101);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(audit, never()).recentEncounters(101);
        verify(audit, never()).recordAdminAccess(SUBJECT, "ADMIN_ENCOUNTERS_LISTED", null);
    }

    @Test
    void returnsUnavailableWhenAuditStorageCannotConfirmAnAdminRead() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("offline"))
            .when(audit).recordAdminAccess(SUBJECT, "ADMIN_ENCOUNTERS_LISTED", null);

        var response = controller.encounters(admin, 20);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void exposesOnlyBoundedFailedAttemptMetadataToAuditedAdminReads() {
        when(failedAttempts.findRecentAuditRecords(20)).thenReturn(List.of());

        var response = controller.failedAttempts(admin, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(audit).recordAdminAccess(SUBJECT, "ADMIN_FAILED_ATTEMPTS_READ", null);
        verify(failedAttempts).findRecentAuditRecords(20);
    }

    @Test
    void rejectsUnboundedFailedAttemptRequestsBeforeLoggingOrQuerying() {
        var response = controller.failedAttempts(admin, 101);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(audit, never()).recordAdminAccess(SUBJECT, "ADMIN_FAILED_ATTEMPTS_READ", null);
        verify(failedAttempts, never()).findRecentAuditRecords(101);
    }
}
