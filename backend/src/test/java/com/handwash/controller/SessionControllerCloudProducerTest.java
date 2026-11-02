package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.ProducerBuildIdentityRequest;
import com.handwash.api.v1.dto.ProducerEpochResponse;
import com.handwash.security.SessionTokenResolver;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class SessionControllerCloudProducerTest {
    private final SessionManager sessions = mock(SessionManager.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final SessionController controller = cloudController();
    private final Jwt producer = Jwt.withTokenValue("test-access-token")
        .header("alg", "none")
        .issuedAt(Instant.now().minusSeconds(2))
        .expiresAt(Instant.now().plusSeconds(60))
        .subject("producer-client")
        .claim("client_id", "producer-client")
        .build();
    private final ProducerBuildIdentityRequest build = new ProducerBuildIdentityRequest(
        "model-r3", "a".repeat(64), "dataset-r4");

    @Test
    void rejectsUnreviewedBuildBeforeRotatingProducerEpoch() {
        doThrow(new IllegalArgumentException("mismatch"))
            .when(audit).validateProducerBuild(build);

        ResponseEntity<?> response = controller.registrarProducerEpoch(
            UUID.randomUUID().toString(), null, "session-capability", build, producer);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("PRODUCTOR_RELEASE_NO_COINCIDE",
            assertInstanceOf(ApiErrorResponse.class, response.getBody()).error());
        verify(sessions, never()).registrarProducerEpoch(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void recordsAuthenticatedProducerAndMatchedBuildAfterRegisteringEpoch() {
        String sessionId = UUID.randomUUID().toString();
        when(sessions.registrarProducerEpoch(sessionId, "session-capability"))
            .thenReturn(new SessionManager.ProducerEpochResult(
                SessionManager.ProducerEpochOutcome.REGISTERED,
                new SessionManager.ProducerEpochRegistration("epoch-1", 2, false)));

        ResponseEntity<?> response = controller.registrarProducerEpoch(
            sessionId, null, "session-capability", build, producer);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("epoch-1", assertInstanceOf(ProducerEpochResponse.class, response.getBody())
            .producerEpoch());
        verify(audit).validateProducerBuild(build);
        verify(audit).recordProducerEpoch(sessionId, "producer-client", "epoch-1", false);
    }

    private SessionController cloudController() {
        SessionController result = new SessionController(sessions, null, null, null, null,
            null, null, new SessionTokenResolver(), audit, null);
        ReflectionTestUtils.setField(result, "producerJwtEnabled", true);
        return result;
    }
}
