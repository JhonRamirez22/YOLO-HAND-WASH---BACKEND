package com.handwash.service;

import com.handwash.model.WashSession;
import com.handwash.model.SessionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

class ContinuityCameraLinkServiceTest {
    @SuppressWarnings("unchecked")
    @Test void ownerProducerLinksWithoutExposingTokenInUrlOrResult() throws Exception {
        var sessions = mock(SessionManager.class);
        var session = mock(WashSession.class);
        when(sessions.getSesion("session")).thenReturn(session);
        when(session.getEstadoSesion()).thenReturn(SessionStatus.ESPERANDO_INICIO);
        when(sessions.getOwnerToken("session")).thenReturn("owner-secret");
        var client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(202);
        when(response.body()).thenReturn("{\"status\":\"reconnecting\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var result = new ContinuityCameraLinkService(sessions, new ObjectMapper(), client).link("session");
        assertEquals("reconnecting", result.status());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://127.0.0.1:8091/camera/link", request.getValue().uri().toString());
        assertEquals("owner-secret", request.getValue().headers().firstValue("X-Session-Token").orElseThrow());
        assertFalse(result.toString().contains("owner-secret"));
    }

    @Test void terminalSessionNeverContactsCamera() {
        var sessions = mock(SessionManager.class);
        var session = mock(WashSession.class);
        when(sessions.getSesion("session")).thenReturn(session);
        when(session.getEstadoSesion()).thenReturn(SessionStatus.EXPIRADA);
        var client = mock(HttpClient.class);
        assertEquals("conflict", new ContinuityCameraLinkService(sessions, new ObjectMapper(), client).link("session").status());
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    @Test void absentCaptureReturnsActionableFailure() throws Exception {
        var sessions = mock(SessionManager.class);
        when(sessions.getSesion("session")).thenReturn(mock(WashSession.class));
        when(sessions.getDeviceToken("session")).thenReturn("device-secret");
        var client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("connection refused"));
        assertEquals("unavailable", new ContinuityCameraLinkService(sessions, new ObjectMapper(), client).link("session").status());
    }
}
