package com.handwash.controller;

import com.handwash.model.WashSession;
import com.handwash.model.ProtocolType;
import com.handwash.service.ContinuityCameraLinkService;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalCameraLinkControllerTest {
    @Test void codeFreePairingNeverLoadsInHospitalOrOrdinaryDevelopmentProfile() {
        var expression = Profiles.of(LocalCameraLinkController.class.getAnnotation(Profile.class).value());
        assertTrue(expression.matches("station-demo"::equals));
        assertTrue(expression.matches("diagnostics"::equals));
        assertFalse(expression.matches("station"::equals));
        assertFalse(expression.matches(p -> p.equals("station") || p.equals("station-demo")));
        assertFalse(expression.matches("development"::equals));
    }

    private MockHttpServletRequest localRequest() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addHeader("Origin", "http://localhost:8080");
        request.addHeader("X-Handwash-Local-UI", "1");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        return request;
    }

    @Test void acceptsOnlyLoopbackSameOriginLocalPage() {
        var request = localRequest();
        assertTrue(LocalCameraLinkController.trustedLocalPage(request));
        request.removeHeader("Origin");
        assertFalse(LocalCameraLinkController.trustedLocalPage(request));
        request.addHeader("Origin", "http://attacker.example");
        assertFalse(LocalCameraLinkController.trustedLocalPage(request));
        request = localRequest();
        request.setRemoteAddr("192.168.1.2");
        assertFalse(LocalCameraLinkController.trustedLocalPage(request));
        request = localRequest();
        request.removeHeader("X-Handwash-Local-UI");
        assertFalse(LocalCameraLinkController.trustedLocalPage(request));
        request = localRequest();
        request.removeHeader("Sec-Fetch-Site");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertFalse(LocalCameraLinkController.trustedLocalPage(request));
    }

    @Test void absentCameraSessionIsActionableAndDoesNotCreateAnotherSession() {
        var sessions = mock(SessionManager.class);
        var camera = mock(ContinuityCameraLinkService.class);
        var limiter = mock(RequestRateLimiter.class);
        when(limiter.allowPairing("127.0.0.1")).thenReturn(true);
        var result = new LocalCameraLinkController(sessions, camera, limiter).link(localRequest());
        assertEquals(503, result.getStatusCode().value());
        assertNull(result.getBody().session());
        verifyNoInteractions(camera);
    }

    @Test void rejectedOriginNeverIssuesCredentialsOrContactsCamera() {
        var sessions = mock(SessionManager.class);
        var camera = mock(ContinuityCameraLinkService.class);
        var limiter = mock(RequestRateLimiter.class);
        var request = localRequest();
        request.removeHeader("Origin");
        assertEquals(403, new LocalCameraLinkController(sessions, camera, limiter).link(request).getStatusCode().value());
        verifyNoInteractions(sessions, camera, limiter);
    }

    @Test void rateLimitStopsRepeatedBootstrap() {
        var sessions = mock(SessionManager.class);
        var camera = mock(ContinuityCameraLinkService.class);
        var limiter = mock(RequestRateLimiter.class);
        assertEquals(429, new LocalCameraLinkController(sessions, camera, limiter).link(localRequest()).getStatusCode().value());
        verifyNoInteractions(sessions, camera);
    }

    @Test void successfulLinkIssuesOnlyViewerForTheExistingCameraSession() {
        var sessions = mock(SessionManager.class);
        var camera = mock(ContinuityCameraLinkService.class);
        var limiter = mock(RequestRateLimiter.class);
        var session = mock(WashSession.class);
        when(limiter.allowPairing("127.0.0.1")).thenReturn(true);
        when(sessions.getSesionActiva()).thenReturn(session);
        when(session.getSessionId()).thenReturn("camera-session");
        when(session.getProtocolo()).thenReturn(ProtocolType.DOMESTICO);
        when(camera.link("camera-session")).thenReturn(new ContinuityCameraLinkService.Result("connected", "Conectada"));
        when(sessions.obtenerTokenDashboardLocal("camera-session"))
            .thenReturn(new SessionManager.TokenIssue("viewer-token", 1800000000000L));
        var response = new LocalCameraLinkController(sessions, camera, limiter).link(localRequest());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("VIEWER", response.getBody().session().role());
        assertEquals("camera-session", response.getBody().session().sessionId());
        verify(sessions).obtenerTokenDashboardLocal("camera-session");
        verify(sessions, never()).emitirTokenDispositivo(anyString());
    }
}
