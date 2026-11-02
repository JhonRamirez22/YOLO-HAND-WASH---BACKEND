package com.handwash.controller;

import com.handwash.agent.DetectionReceiver;
import com.handwash.api.v1.dto.ProducerBootstrapRequest;
import com.handwash.model.StationRecord;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProducerBootstrapControllerTest {
    @Test
    void onlyTheBoundM2mClientReceivesADeviceCapabilityForItsActiveStationSession() {
        String stationId = "d398e61f-56d9-47d3-9381-bdff87731931";
        SessionManager sessions = new SessionManager(new DetectionReceiver());
        String sessionId = sessions.crearSesion(ProtocolType.DOMESTICO, true);
        StationCredentialService stations = mock(StationCredentialService.class);
        SessionAuditService audit = mock(SessionAuditService.class);
        when(stations.activeStation(stationId)).thenReturn(new StationRecord(stationId,
            "Puesto RTX", 1, "admin", null, null, sessionId, "request", "producer-client"));
        ProducerBootstrapController controller = new ProducerBootstrapController(stations, sessions, audit);
        ProducerBootstrapRequest request = new ProducerBootstrapRequest(stationId,
            "yolo26n_7pasos_b64-unvalidated",
            "9c1198467ef84c108b093f2c3da8d73d313b96855e2d42ec5f4b9729577d30e7",
            "training-yaml-not-provided");
        Jwt authorized = jwt("producer-client");
        Jwt other = jwt("another-client");
        try {
            assertEquals(403, controller.bootstrap(request, other).getStatusCode().value());
            verify(audit, never()).validateProducerBuild(any());

            var response = controller.bootstrap(request, authorized);
            assertEquals(200, response.getStatusCode().value());
            var body = (com.handwash.api.v1.dto.ProducerBootstrapResponse) response.getBody();
            assertNotNull(body);
            assertEquals(sessionId, body.sessionId());
            assertEquals(2, body.protocolVersion());
            assertNotNull(body.producerEpoch());
            assertTrue(body.sessionToken().length() >= 32);
            verify(audit).validateProducerBuild(any());
            verify(audit).recordProducerEpoch(eq(sessionId), eq("producer-client"),
                eq(body.producerEpoch()), anyBoolean());
        } finally {
            sessions.eliminarSesion(sessionId);
        }
    }

    @Test
    void inactiveStationReturnsRetryableConflictWithoutIssuingCredentials() {
        String stationId = "d398e61f-56d9-47d3-9381-bdff87731931";
        StationCredentialService stations = mock(StationCredentialService.class);
        SessionManager sessions = new SessionManager(new DetectionReceiver());
        SessionAuditService audit = mock(SessionAuditService.class);
        when(stations.activeStation(stationId)).thenReturn(new StationRecord(stationId,
            "Puesto RTX", 1, "admin", null, null, null, null, "producer-client"));
        ProducerBootstrapController controller = new ProducerBootstrapController(stations, sessions, audit);
        ProducerBootstrapRequest request = new ProducerBootstrapRequest(stationId,
            "yolo26n_7pasos_b64-unvalidated",
            "9c1198467ef84c108b093f2c3da8d73d313b96855e2d42ec5f4b9729577d30e7",
            "training-yaml-not-provided");

        var response = controller.bootstrap(request, jwt("producer-client"));

        assertEquals(409, response.getStatusCode().value());
        verify(stations, times(1)).activeStation(stationId);
        verify(audit, never()).validateProducerBuild(any());
    }

    private static Jwt jwt(String clientId) {
        Instant now = Instant.now();
        return new Jwt("token", now, now.plusSeconds(60), Map.of("alg", "none"),
            Map.of("sub", clientId, "client_id", clientId));
    }

    @Test
    void theStationIsDerivedFromTheProducerKeyWhenTheRequestOmitsIt() {
        String stationId = "d398e61f-56d9-47d3-9381-bdff87731931";
        SessionManager sessions = new SessionManager(new DetectionReceiver());
        String sessionId = sessions.crearSesion(ProtocolType.DOMESTICO, true);
        StationCredentialService stations = mock(StationCredentialService.class);
        SessionAuditService audit = mock(SessionAuditService.class);
        when(stations.activeStationByProducerClient("apikey_0123456789abcdef")).thenReturn(new StationRecord(stationId,
            "Puesto RTX", 1, "admin", null, null, sessionId, "request", "apikey_0123456789abcdef"));
        when(stations.activeStation(stationId)).thenReturn(new StationRecord(stationId,
            "Puesto RTX", 1, "admin", null, null, sessionId, "request", "apikey_0123456789abcdef"));
        ProducerBootstrapController controller = new ProducerBootstrapController(stations, sessions, audit);
        var build = new ProducerBootstrapRequest(null, "yolo26n_7pasos_b64-unvalidated",
            "9c1198467ef84c108b093f2c3da8d73d313b96855e2d42ec5f4b9729577d30e7", "training-yaml-not-provided");
        try {
            var ok = controller.bootstrap(build, jwt("apikey_0123456789abcdef"));
            assertEquals(200, ok.getStatusCode().value());
            // A key bound to no station (or a revoked one) gets nothing, and a malformed id is still refused.
            assertEquals(403, controller.bootstrap(build, jwt("apikey_ffffffffffffffff")).getStatusCode().value());
            assertEquals(400, controller.bootstrap(new ProducerBootstrapRequest("not-a-uuid", null, null, null),
                jwt("apikey_0123456789abcdef")).getStatusCode().value());
        } finally {
            sessions.eliminarSesion(sessionId);
        }
    }
}
