package com.handwash.controller;

import com.handwash.service.SessionManager;
import com.handwash.service.ContinuityCameraLinkService;
import com.handwash.security.SessionTokenResolver;
import com.handwash.model.WashSession;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ContinuityCameraControllerTest {
    @Test void linkRequiresCurrentSessionCapability() {
        var sessions = mock(SessionManager.class);
        var camera = mock(ContinuityCameraLinkService.class);
        when(sessions.getSesion("session")).thenReturn(mock(WashSession.class));
        var controller = new ContinuityCameraController(sessions, new SessionTokenResolver(), camera);
        assertEquals(401, controller.link("session", "Bearer invalid", null).getStatusCode().value());
        verifyNoInteractions(camera);
        when(sessions.tieneAccesoLectura("session", "viewer")).thenReturn(true);
        when(camera.link("session")).thenReturn(new ContinuityCameraLinkService.Result("reconnecting", "Buscando"));
        assertEquals(202, controller.link("session", "Bearer viewer", null).getStatusCode().value());
        verify(camera).link("session");
    }
}
