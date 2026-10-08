package com.handwash.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.handwash.agent.Receiver;
import com.handwash.api.v1.dto.ActiveSessionResponse;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.SessionDetailsResponse;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionPairRequest;
import com.handwash.api.v1.mapper.ProtocolCatalogMapper;
import com.handwash.api.v1.mapper.SessionResponseMapper;
import com.handwash.model.ProtocolType;
import com.handwash.security.SessionTokenResolver;
import com.handwash.service.SessionManager;
import com.handwash.service.RequestRateLimiter;
import com.handwash.security.SessionAuthenticationService;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionControllerTest {
    @Test
    void activeSessionReturnsVersionedDtoWithTheExistingJsonContract() {
        SessionManager sessionManager = new SessionManager(new Receiver());
        String sessionId = sessionManager.crearSesion(ProtocolType.DOMESTICO);
        // This focused path uses only SessionManager; other controller collaborators are not invoked.
        SessionController controller = new SessionController(
            sessionManager, null, null, null, null, null, null, new SessionTokenResolver());

        try {
            ResponseEntity<?> response = controller.obtenerSesionActiva();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            ActiveSessionResponse dto = assertInstanceOf(ActiveSessionResponse.class, response.getBody());
            assertEquals(sessionId, dto.sessionId());
            assertEquals("DOMESTICO", dto.protocolo());
            assertEquals("ESPERANDO_INICIO", dto.estado());
            assertEquals(true, dto.accessRequired());

            JsonNode json = new ObjectMapper().valueToTree(dto);
            Set<String> fields = new HashSet<>();
            fields.addAll(json.propertyNames());
            assertEquals(Set.of("sessionId", "protocolo", "estado", "accessRequired"), fields);
        } finally {
            sessionManager.eliminarSesion(sessionId);
        }
    }

    @Test
    void sessionDetailsAuthenticateAndBuildOneSnapshotUnderTheSessionLock() {
        LockCheckingSessionManager manager = new LockCheckingSessionManager();
        SessionController controller = new SessionController(
            manager, new ProtocolCatalogMapper(new ValidationRuleStrategyFactory()),
            new SessionResponseMapper(), null, null, null, null, new SessionTokenResolver());

        ResponseEntity<?> response = controller.obtenerSesion(manager.sessionId, null, "test-token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(SessionDetailsResponse.class, response.getBody());
        assertTrue(manager.readAuthorizationWasLocked,
            "authorization and the session DTO must share the same domain revision");
        assertTrue(manager.ownerAuthorizationWasLocked,
            "owner-only pairing-code projection must use the same locked snapshot");
    }

    @Test
    void failedAttemptHistoryReturnsUnavailableInsteadOfAStaleSuccessfulResponse() {
        FailingPersistenceSessionManager manager = new FailingPersistenceSessionManager();
        SessionController controller = new SessionController(
            manager, null, null, null, null, null, null, new SessionTokenResolver());

        ResponseEntity<?> response = controller.obtenerIntentosFallidos(
            manager.sessionId, null, manager.getOwnerToken(manager.sessionId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        ApiErrorResponse error = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("CACHE_INTENTOS_NO_DISPONIBLE", error.error());
    }

    @Test
    void failedAttemptCacheDeletionDoesNotClaimSessionWasDeleted() {
        FailingPersistenceSessionManager manager = new FailingPersistenceSessionManager();
        SessionController controller = new SessionController(
            manager, null, null, null, null, null, null, new SessionTokenResolver());

        ResponseEntity<?> response = controller.eliminarSesion(
            manager.sessionId, null, manager.getOwnerToken(manager.sessionId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        ApiErrorResponse error = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("ELIMINACION_NO_CONFIRMADA", error.error());
        assertEquals(manager.sessionId, manager.getSesion(manager.sessionId).getSessionId(),
            "a failed cache deletion must leave the session available for retry");
    }

    @Test
    void stationRejectsSessionCreationWithoutV2BeforeCreatingLegacySession() {
        SessionManager manager = new SessionManager(new Receiver());
        SessionController controller = controller(manager);
        ReflectionTestUtils.setField(controller, "producerV2Required", true);

        ResponseEntity<?> response = controller.crearSesion(
            new SessionCreateRequest("DOMESTICO", null), localRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiErrorResponse error = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("CAPTURADOR_PROTOCOL_VERSION_REQUIRED", error.error());
        assertEquals("2", error.producerProtocolVersion());
        assertEquals(0, manager.getSesiones().size());
    }

    @Test
    void stationRejectsLegacyPairingEvenForAnExistingLegacySession() {
        SessionManager manager = new SessionManager(new Receiver());
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        String existingDeviceToken = manager.getDeviceToken(sessionId);
        SessionController controller = controller(manager);
        ReflectionTestUtils.setField(controller, "producerV2Required", true);

        ResponseEntity<?> response = controller.vincularSesion(
            new SessionPairRequest(manager.getCodigoEmparejamiento(sessionId), null), localRequest());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiErrorResponse error = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("CAPTURADOR_PROTOCOL_VERSION_REQUIRED", error.error());
        assertEquals("2", error.producerProtocolVersion());
        assertEquals(existingDeviceToken, manager.getDeviceToken(sessionId),
            "rejecting v1 must not rotate the existing device credential");
        assertEquals(1, manager.getSesiones().size(),
            "the existing session must remain and no replacement session may be created");
        manager.eliminarSesion(sessionId);
    }

    private SessionController controller(SessionManager manager) {
        return new SessionController(manager, null, null, null, new RequestRateLimiter(),
            null, new SessionAuthenticationService(manager), new SessionTokenResolver());
    }

    private MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static final class FailingPersistenceSessionManager extends SessionManager {
        private final String sessionId;

        private FailingPersistenceSessionManager() {
            super(new Receiver());
            sessionId = crearSesion(ProtocolType.DOMESTICO);
        }

        @Override
        public boolean persistirIntentosFallidosPendientes(String ignored) {
            return false;
        }

        @Override
        public boolean eliminarSesion(String ignored) {
            return false;
        }
    }

    private static final class LockCheckingSessionManager extends SessionManager {
        private final String sessionId;
        private boolean readAuthorizationWasLocked;
        private boolean ownerAuthorizationWasLocked;

        private LockCheckingSessionManager() {
            super(new Receiver());
            sessionId = crearSesion(ProtocolType.DOMESTICO);
        }

        @Override
        public boolean tieneAccesoLectura(String ignoredSessionId, String ignoredToken) {
            readAuthorizationWasLocked = Thread.holdsLock(getSesion(sessionId));
            return readAuthorizationWasLocked;
        }

        @Override
        public boolean tieneAcceso(String ignoredSessionId, String ignoredToken,
                                   boolean ignoredOwnerOnly) {
            ownerAuthorizationWasLocked = Thread.holdsLock(getSesion(sessionId));
            return ownerAuthorizationWasLocked;
        }
    }
}
