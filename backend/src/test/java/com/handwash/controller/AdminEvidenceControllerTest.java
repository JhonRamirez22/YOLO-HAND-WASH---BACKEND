package com.handwash.controller;

import com.handwash.service.SessionAuditService;
import com.handwash.service.evidence.EvidenceSettingsService;
import com.handwash.service.evidence.EvidenceStore;
import com.handwash.service.evidence.WashEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminEvidenceControllerTest {
    private static final String SESSION = "44444444-4444-4444-4444-444444444444";

    private final EvidenceSettingsService settings = mock(EvidenceSettingsService.class);
    private final WashEvidenceRepository repository = mock(WashEvidenceRepository.class);
    private final EvidenceStore store = mock(EvidenceStore.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminEvidenceController controller = new AdminEvidenceController(settings, repository, store, audit);
    private final Jwt admin = mock(Jwt.class);

    AdminEvidenceControllerTest() { when(admin.getSubject()).thenReturn("admin-sub"); }

    @Test
    void servesThePhotoPrivatelyAfterAuditingTheView() {
        byte[] bytes = {9, 8, 7};
        when(repository.findObjectKey(SESSION, 1)).thenReturn(Optional.of("reports/a.jpg"));
        when(store.get("reports/a.jpg")).thenReturn(Optional.of(bytes));

        var response = controller.photo(admin, SESSION, 1);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(bytes, (byte[]) response.getBody());
        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_EVIDENCE_PHOTO_VIEWED", SESSION);
    }

    @Test
    void failsClosedWhenTheViewCannotBeAudited() {
        when(repository.findObjectKey(SESSION, 1)).thenReturn(Optional.of("reports/a.jpg"));
        doThrow(new DataAccessResourceFailureException("audit down"))
            .when(audit).recordAdminAccess("admin-sub", "ADMIN_EVIDENCE_PHOTO_VIEWED", SESSION);

        assertEquals(503, controller.photo(admin, SESSION, 1).getStatusCode().value());
        verify(store, never()).get("reports/a.jpg");
    }

    @Test
    void reportsMissingPhotosAndBadAddressing() {
        when(repository.findObjectKey(SESSION, 1)).thenReturn(Optional.empty());
        assertEquals(404, controller.photo(admin, SESSION, 1).getStatusCode().value());

        when(repository.findObjectKey(SESSION, 2)).thenReturn(Optional.of("reports/gone.jpg"));
        when(store.get("reports/gone.jpg")).thenReturn(Optional.empty());
        assertEquals(404, controller.photo(admin, SESSION, 2).getStatusCode().value());

        assertEquals(400, controller.photo(admin, "../etc", 1).getStatusCode().value());
        assertEquals(400, controller.photo(admin, SESSION, 0).getStatusCode().value());
        assertEquals(401, controller.photo(null, SESSION, 1).getStatusCode().value());
    }

    @Test
    void storeOutageIsServiceUnavailable() {
        when(repository.findObjectKey(SESSION, 1)).thenReturn(Optional.of("reports/a.jpg"));
        when(store.get("reports/a.jpg")).thenThrow(new EvidenceStore.EvidenceStoreException("s3 down"));

        assertEquals(503, controller.photo(admin, SESSION, 1).getStatusCode().value());
    }

    @Test
    void killSwitchRequiresAnExplicitBooleanAndAConfiguredDeployment() {
        assertEquals(400, controller.change(admin, null).getStatusCode().value());
        assertEquals(400, controller.change(admin, new AdminEvidenceController.SwitchRequest(null)).getStatusCode().value());

        when(settings.setEnabled(true, "admin-sub")).thenThrow(new IllegalStateException("FOTO_NO_CONFIGURADA"));
        assertEquals(409, controller.change(admin, new AdminEvidenceController.SwitchRequest(true)).getStatusCode().value());

        assertEquals(401, controller.change(null, new AdminEvidenceController.SwitchRequest(false)).getStatusCode().value());
    }

    @Test
    void readingTheSettingsIsAudited() {
        assertEquals(200, controller.view(admin).getStatusCode().value());
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_EVIDENCE_SETTINGS_READ", null);
    }
}
