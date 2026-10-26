package com.handwash.api.v1;

import com.handwash.api.v1.dto.ProfileChangeResponse;
import com.handwash.model.ProfileChange;
import com.handwash.model.ProtocolProfile;
import com.handwash.repository.ChangeControlRepository;
import com.handwash.service.ChangeControlService;
import com.handwash.service.ProtocolProfileService;
import com.handwash.service.SessionAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminChangeControlControllerTest {
    private final ProtocolProfileService profiles = mock(ProtocolProfileService.class);
    private final ChangeControlService control = mock(ChangeControlService.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminChangeControlController controller = new AdminChangeControlController(profiles, control, audit);
    private final Jwt admin = mock(Jwt.class);

    AdminChangeControlControllerTest() { when(admin.getSubject()).thenReturn("admin-b"); }

    private static ProfileChange change(int version, String state) {
        return new ProfileChange(new ProtocolProfile(version, ProtocolProfile.uniforme(7_000L), 60_000L, "admin-a", 1L),
            "Motivo suficientemente largo", null, state, null, null);
    }

    @Test
    void overviewIsAuditedAndMarksTheVersionInForceAndItsEvidence() {
        when(profiles.cambios(30)).thenReturn(List.of(change(2, "PENDING"), change(1, "ACTIVE")));
        when(control.settings()).thenReturn(new ChangeControlRepository.Settings(true, "admin-a", 1L));
        when(control.models()).thenReturn(List.of());
        when(control.reports(30)).thenReturn(List.of());
        when(control.profileHasReport(1)).thenReturn(true);

        var response = controller.overview(admin);

        assertEquals(200, response.getStatusCode().value());
        var body = (AdminChangeControlController.Overview) response.getBody();
        assertEquals(1, body.vigente().perfil().version());
        assertTrue(body.vigente().conInformeDeValidacion());
        assertEquals("PENDING", body.cambios().get(0).estado());
        verify(audit).recordAdminAccess("admin-b", "ADMIN_CHANGE_CONTROL_READ", null);
    }

    @Test
    void approveReturnsTheActivatedVersionAndTheSameAdminGets403() {
        when(profiles.aprobar(2, "admin-b")).thenReturn(change(2, "ACTIVE"));
        var ok = controller.approve(admin, 2);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("ACTIVE", ((ProfileChangeResponse) ok.getBody()).estado());

        when(profiles.aprobar(3, "admin-b")).thenThrow(new ProtocolProfileService.SameApproverException());
        var denied = controller.approve(admin, 3);
        assertEquals(403, denied.getStatusCode().value());
        assertTrue(String.valueOf(denied.getBody()).contains("SE_REQUIERE_OTRO_ADMIN"));
    }

    @Test
    void revertForwardsTheReasonAndMapsDomainErrors() {
        when(profiles.revertir(1, "admin-b", "Volver al perfil inicial por fatiga")).thenReturn(change(3, "ACTIVE"));
        assertEquals(200, controller.revert(admin, 1, new AdminChangeControlController.ReasonRequest("Volver al perfil inicial por fatiga")).getStatusCode().value());

        when(profiles.revertir(anyInt(), anyString(), org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalArgumentException("MOTIVO_INVALIDO"));
        var bad = controller.revert(admin, 1, null);
        assertEquals(400, bad.getStatusCode().value());
        assertTrue(String.valueOf(bad.getBody()).contains("MOTIVO_INVALIDO"));
    }

    @Test
    void storageOutageIs503AndAnonymousIs401() {
        when(profiles.rechazar(2, "admin-b")).thenThrow(new DataAccessResourceFailureException("db down"));
        assertEquals(503, controller.reject(admin, 2).getStatusCode().value());
        assertEquals(401, controller.overview(null).getStatusCode().value());
        assertEquals(401, controller.approve(null, 1).getStatusCode().value());
        assertEquals(401, controller.registerReport(null, null).getStatusCode().value());
        verify(profiles, never()).aprobar(anyInt(), anyString());
    }

    @Test
    void settingsNeedAnExplicitBooleanAndReportsMapValidationErrors() {
        assertEquals(400, controller.settings(admin, null).getStatusCode().value());
        assertEquals(400, controller.settings(admin, new AdminChangeControlController.SettingsRequest(null)).getStatusCode().value());
        when(control.setSecondApproverRequired(true, "admin-b")).thenReturn(new ChangeControlRepository.Settings(true, "admin-b", 2L));
        assertEquals(200, controller.settings(admin, new AdminChangeControlController.SettingsRequest(true)).getStatusCode().value());

        when(control.registerReport(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyString())).thenThrow(new IllegalArgumentException("MODELO_DESCONOCIDO"));
        var response = controller.registerReport(admin, new AdminChangeControlController.ReportRequest("a".repeat(64), null, "T", "R", "d".repeat(64)));
        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).contains("MODELO_DESCONOCIDO"));
    }
}
