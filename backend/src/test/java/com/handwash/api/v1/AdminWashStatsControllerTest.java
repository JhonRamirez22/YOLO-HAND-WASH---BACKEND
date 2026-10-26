package com.handwash.api.v1;

import com.handwash.model.WashOutcome;
import com.handwash.model.WashExportRow;
import com.handwash.repository.FrictionAttemptRepository;
import com.handwash.service.SessionAuditService;
import com.handwash.service.WashReportAnalytics;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWashStatsControllerTest {
    private static final long NOW = 1_792_600_000_000L;
    private final FrictionAttemptRepository repository = mock(FrictionAttemptRepository.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminWashStatsController controller = new AdminWashStatsController(repository, audit, ZoneId.of("America/Bogota"), () -> NOW);
    private final Jwt admin = mock(Jwt.class);

    AdminWashStatsControllerTest() { when(admin.getSubject()).thenReturn("admin-sub"); }

    private static WashExportRow row() {
        return new WashExportRow(WashOutcome.CORRECTO, null, NOW - 44_000, NOW, 44_000L, false, Map.of("PASO_1_PALMAS", "VALIDADO"));
    }

    @Test
    void trendsAreAuditedAndComputedFromAnonymousRows() {
        when(repository.findExportRows(anyLong(), anyLong(), any(), anyInt())).thenReturn(List.of(row()));

        var response = controller.trends(admin, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, ((WashReportAnalytics.Tendencias) response.getBody()).total().correctos());
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_WASH_TRENDS_READ", null);
    }

    @Test
    void exportIsACsvAttachmentWithoutCachingAndAuditedBeforeTheDataIsRead() {
        when(repository.findExportRows(anyLong(), anyLong(), any(), anyInt())).thenReturn(List.of(row()));

        var response = controller.export(admin, NOW - TimeUnit.DAYS.toMillis(2), NOW, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("text/csv"));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").startsWith("attachment; filename=\"handwash-lavados-"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(new String((byte[]) response.getBody(), StandardCharsets.UTF_8).contains("CORRECTO"));
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_WASH_REPORTS_EXPORTED", null);
    }

    @Test
    void anExportThatCannotBeAuditedIsNotProduced() {
        doThrow(new DataAccessResourceFailureException("audit down")).when(audit)
            .recordAdminAccess("admin-sub", "ADMIN_WASH_REPORTS_EXPORTED", null);

        assertEquals(503, controller.export(admin, null, null, null).getStatusCode().value());
        verify(repository, never()).findExportRows(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void rejectsBadFiltersAndRangesThatAreTooBigOrTooWide() {
        assertEquals(400, controller.export(admin, null, null, "NOPE").getStatusCode().value());
        assertEquals(400, controller.export(admin, 10L, 5L, null).getStatusCode().value());
        assertEquals(400, controller.trends(admin, 0L, TimeUnit.DAYS.toMillis(200)).getStatusCode().value());

        when(repository.findExportRows(anyLong(), anyLong(), any(), anyInt()))
            .thenReturn(java.util.Collections.nCopies(AdminWashStatsController.MAX_ROWS + 1, row()));
        var tooMany = controller.export(admin, null, null, null);
        assertEquals(400, tooMany.getStatusCode().value());
        assertTrue(String.valueOf(tooMany.getBody()).contains("RANGO_DEMASIADO_GRANDE"));
    }

    @Test
    void anonymousCallersAreRejected() {
        assertEquals(401, controller.trends(null, null, null).getStatusCode().value());
        assertEquals(401, controller.export(null, null, null, null).getStatusCode().value());
    }
}
