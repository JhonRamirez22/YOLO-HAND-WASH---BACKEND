package com.handwash.preview;

import com.handwash.api.v1.dto.DetectionRequest;
import com.handwash.api.v1.mapper.DetectionRequestMapper;
import com.handwash.model.DetectionEvent;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DetectionIngressHandlerTest {
    private SessionManager sessions;
    private DetectionRequestMapper mapper;
    private DetectionIngressHandler handler;
    private WebSocketSession socket;
    private static final String DETECTION = "{\"sessionId\":\"s1\",\"claseDetectada\":\"PALMAS\",\"confianza\":0.9,\"eventType\":\"DETECTION\",\"frameSequence\":3}";

    @BeforeEach
    void setUp() throws Exception {
        sessions = mock(SessionManager.class);
        mapper = mock(DetectionRequestMapper.class);
        handler = new DetectionIngressHandler(sessions, mapper, new ObjectMapper());
        socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("ws-1");
        when(socket.isOpen()).thenReturn(true);
        when(socket.getUri()).thenReturn(new URI("wss://api.example/ws/detections-in/s1"));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        when(sessions.autenticar("s1", "device-token")).thenReturn(new SessionManager.AccessInfo(SessionManager.AccessRole.DEVICE, null, null, 1L));
        when(mapper.toDomain(any(DetectionRequest.class))).thenReturn(mock(DetectionEvent.class));
        when(sessions.procesarDeteccionHttp(any(), eq("device-token"))).thenReturn(
            new SessionManager.DetectionResult(SessionManager.DetectionOutcome.ACCEPTED, null, null, null, null));
    }

    @Test
    void afterAuthenticatingWithTheSessionTokenEveryFrameIsProcessedAndAcceptedOnesAreSilent() throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"authToken\":\"device-token\"}"));
        handler.handleTextMessage(socket, new TextMessage(DETECTION));
        handler.handleTextMessage(socket, new TextMessage(DETECTION));

        verify(sessions, times(2)).procesarDeteccionHttp(any(), eq("device-token"));
        verify(socket, never()).close(any());
        verify(socket, never()).sendMessage(any());
    }

    @Test
    void aWrongTokenClosesTheSocketAndNothingIsProcessed() throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"authToken\":\"nope\"}"));
        handler.handleTextMessage(socket, new TextMessage(DETECTION));

        verify(socket, atLeastOnce()).close(any(CloseStatus.class));
        verify(sessions, never()).procesarDeteccionHttp(any(), any());
    }

    @Test
    void aFrameBeforeAuthenticatingIsRefused() throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage(DETECTION));
        verify(socket).close(any(CloseStatus.class));
        verify(sessions, never()).procesarDeteccionHttp(any(), any());
    }

    @Test
    void rejectionsAreReportedBackWithTheirReasonSoTheProducerCanReact() throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"authToken\":\"device-token\"}"));
        when(sessions.procesarDeteccionHttp(any(), eq("device-token"))).thenReturn(
            new SessionManager.DetectionResult(SessionManager.DetectionOutcome.TERMINAL, null, null, null, null));

        handler.handleTextMessage(socket, new TextMessage(DETECTION));

        verify(socket).sendMessage(argThat(m -> m instanceof TextMessage t && t.getPayload().contains("\"status\":409")));
        verify(socket, never()).close(any());
    }

    @Test
    void malformedJsonIsIgnoredWithoutDroppingTheProducer() throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket, new TextMessage("{\"authToken\":\"device-token\"}"));
        handler.handleTextMessage(socket, new TextMessage("not json"));
        verify(socket, never()).close(any());
    }
}
