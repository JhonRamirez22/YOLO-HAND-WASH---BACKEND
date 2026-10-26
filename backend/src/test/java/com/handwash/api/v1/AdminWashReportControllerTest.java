package com.handwash.api.v1;

import com.handwash.model.WashOutcome;
import com.handwash.repository.FrictionAttemptRepository;
import com.handwash.service.SessionAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWashReportControllerTest {
    private static final long NOW = 1_792_600_000_000L;
    private final FrictionAttemptRepository repo = mock(FrictionAttemptRepository.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminWashReportController controller = new AdminWashReportController(repo, audit, () -> NOW);
    private final Jwt admin = Jwt.withTokenValue("t").header("alg", "none").subject("admin-sub")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

    @Test
    void defaultsToTheLastSevenDaysAndAuditsTheRead() {
        when(repo.findReports(anyLong(), anyLong(), any(), anyInt())).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller.reports(admin, null, null, null, 50).getStatusCode());
        verify(repo).findReports(NOW + 1 - AdminWashReportController.DEFAULT_WINDOW_MS, NOW + 1, null, 50);
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_WASH_REPORTS_READ", null);
    }

    @Test
    void filtersByOutcomeCaseInsensitively() {
        controller.reports(admin, 10L, 20L, "reportado", 5);
        verify(repo).findReports(10L, 20L, WashOutcome.REPORTADO, 5);
    }

    @Test
    void rejectsInvalidFiltersBeforeReadingAudit() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.reports(admin, 10L, 20L, "APROBADO", 5).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.reports(admin, 20L, 10L, null, 5).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.reports(admin, 0L,
            AdminWashReportController.MAX_WINDOW_MS + 1, null, 5).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.reports(admin, 10L, 20L, null, 101).getStatusCode());
        verify(repo, never()).findReports(anyLong(), anyLong(), any(), anyInt());
        verify(audit, never()).recordAdminAccess(any(), any(), any());
    }

    @Test
    void anonymousAndStorageFailures() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.reports(null, null, null, null, 50).getStatusCode());
        when(repo.findReports(anyLong(), anyLong(), any(), anyInt()))
            .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.reports(admin, null, null, null, 50).getStatusCode());
    }
}
