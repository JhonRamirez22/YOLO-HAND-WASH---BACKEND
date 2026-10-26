package com.handwash.api.v1;

import com.handwash.service.SessionAuditService;
import com.handwash.service.audit.AuditChainVerifier;
import com.handwash.service.audit.AuditSealService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditChainControllerTest {
    private final AuditChainVerifier verifier = mock(AuditChainVerifier.class);
    private final AuditSealService seals = mock(AuditSealService.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminAuditChainController controller = new AdminAuditChainController(verifier, seals, audit);
    private final Jwt admin = mock(Jwt.class);

    AdminAuditChainControllerTest() { when(admin.getSubject()).thenReturn("admin-sub"); }

    private static AuditChainVerifier.Report report(boolean ok) {
        return new AuditChainVerifier.Report(ok, true, 3, 1L, 3L, "h".repeat(64), true, null,
            ok ? List.of() : List.of(new AuditChainVerifier.Problem(2L, "HASH_MISMATCH")));
    }

    @Test
    void reportsTheVerificationAndAuditsTheRead() {
        when(verifier.verify()).thenReturn(report(false));
        when(seals.recent(5)).thenReturn(List.of());

        var response = controller.verify(admin);

        assertEquals(200, response.getStatusCode().value());
        var body = (AdminAuditChainController.Status) response.getBody();
        assertFalse(body.chain().ok());
        assertEquals("HASH_MISMATCH", body.chain().problems().get(0).code());
        assertTrue(body.resultsMatchLatestSeal());
        verify(audit).recordAdminAccess("admin-sub", "ADMIN_AUDIT_CHAIN_VERIFIED", null);
    }

    @Test
    void requiresAnAuthenticatedAdminPrincipal() {
        assertEquals(401, controller.verify(null).getStatusCode().value());
        assertEquals(401, controller.seal(null).getStatusCode().value());
        verify(verifier, never()).verify();
    }

    @Test
    void sealingReturnsTheSealOrNoContentAndAnOutageIs503() {
        var seal = new AuditSealService.Seal(1, 2, 0, 3L, "h".repeat(64), 3, "d".repeat(64), 0, true, "s".repeat(64), "seals/x.json");
        when(seals.sealIfNeeded()).thenReturn(Optional.of(seal)).thenReturn(Optional.empty());
        assertEquals(200, controller.seal(admin).getStatusCode().value());
        assertEquals(204, controller.seal(admin).getStatusCode().value());

        when(seals.sealIfNeeded()).thenThrow(new IllegalStateException("bucket down"));
        assertEquals(503, controller.seal(admin).getStatusCode().value());
    }

    @Test
    void aStorageOutageIs503WithoutLeakingDetails() {
        when(verifier.verify()).thenThrow(new DataAccessResourceFailureException("jdbc://secret"));
        var response = controller.verify(admin);
        assertEquals(503, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("secret"));
    }
}
