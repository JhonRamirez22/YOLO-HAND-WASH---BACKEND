package com.handwash.api.v1;

import com.handwash.api.v1.dto.ProtocolProfileRequest;
import com.handwash.api.v1.dto.ProtocolProfileResponse;
import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;
import com.handwash.service.ProtocolProfileService;
import com.handwash.service.SessionAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminProtocolProfileControllerTest {
    private static final String SUBJECT = "cognito-admin-subject";
    private final ProtocolProfileService profiles = mock(ProtocolProfileService.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final AdminProtocolProfileController controller = new AdminProtocolProfileController(profiles, audit);
    private final Jwt admin = Jwt.withTokenValue("opaque-test").header("alg", "none")
        .subject(SUBJECT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

    @Test
    void readIsAuditedAndReturnsProfileInForceWithHistory() {
        when(profiles.perfilVigente()).thenReturn(ProtocolProfile.PREDETERMINADO);
        when(profiles.historial(20)).thenReturn(List.of(ProtocolProfile.PREDETERMINADO));

        var response = controller.overview(admin);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(audit).recordAdminAccess(SUBJECT, "ADMIN_PROTOCOL_PROFILE_READ", null);
    }

    @Test
    void createsVersionWithCanonicalStepCodesAndAdminSubject() {
        ProtocolProfile v2 = ProtocolProfile.PREDETERMINADO
            .siguienteVersion(ProtocolProfile.uniforme(8_000L), 70_000L, SUBJECT, 1L);
        when(profiles.crearVersion(any(), anyLong(), anyString(), any())).thenReturn(
            new com.handwash.model.ProfileChange(v2, "Motivo de prueba largo", null, "ACTIVE", null, null));

        var response = controller.createVersion(admin, request(8_000L, 70_000L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.handwash.api.v1.dto.ProfileChangeResponse) response.getBody();
        assertEquals(2, body.perfil().version());
        assertEquals(48_000L, body.perfil().duracionTotalMs());
        assertEquals("ACTIVE", body.estado());
        verify(profiles).crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, SUBJECT, "Motivo de prueba largo");
    }

    @Test
    void rejectsMissingOrUnknownStepsBeforeTouchingStorage() {
        Map<String, Long> tiempos = new LinkedHashMap<>(request(8_000L, 70_000L).tiemposPasoMs());
        tiempos.remove(WashStep.PASO_6_PUNTA_DE_DEDOS.name());
        tiempos.put(WashStep.PASO_7_CIRCULARES.name(), 8_000L);

        var response = controller.createVersion(admin, new ProtocolProfileRequest(tiempos, 70_000L, "Motivo de prueba largo"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(profiles, never()).crearVersion(any(), anyLong(), anyString(), any());
    }

    @Test
    void mapsDomainRejectionConflictAndStorageFailure() {
        when(profiles.crearVersion(any(), anyLong(), anyString(), any()))
            .thenThrow(new IllegalArgumentException("fuera de rango"))
            .thenThrow(new DuplicateKeyException("version"))
            .thenThrow(new DataAccessResourceFailureException("offline"));

        assertEquals(HttpStatus.BAD_REQUEST, controller.createVersion(admin, request(5_000L, 60_000L)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.createVersion(admin, request(8_000L, 70_000L)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            controller.createVersion(admin, request(8_000L, 70_000L)).getStatusCode());
    }

    @Test
    void anInvalidReasonIsReportedAsSuch() {
        when(profiles.crearVersion(any(), anyLong(), anyString(), any())).thenThrow(new IllegalArgumentException("MOTIVO_INVALIDO"));

        var response = controller.createVersion(admin, request(8_000L, 70_000L));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("MOTIVO_INVALIDO"));
    }

    @Test
    void anonymousCallerIsRejected() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.overview(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.createVersion(null, request(8_000L, 70_000L)).getStatusCode());
    }

    private static ProtocolProfileRequest request(long porPasoMs, long umbralLentoMs) {
        Map<String, Long> tiempos = new LinkedHashMap<>();
        ProtocolProfile.PASOS.forEach(paso -> tiempos.put(paso.name(), porPasoMs));
        return new ProtocolProfileRequest(tiempos, umbralLentoMs, "Motivo de prueba largo");
    }
}
