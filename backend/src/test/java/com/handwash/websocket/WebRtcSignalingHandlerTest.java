package com.handwash.websocket;

import com.handwash.agent.DetectionReceiver;
import com.handwash.model.ProtocolType;
import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import com.handwash.service.SessionManager;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WebRtcSignalingHandlerTest {
    private final SessionManager sessions = new SessionManager(new DetectionReceiver());
    private final WebRtcTicketService tickets = new WebRtcTicketService(sessions, new WebRtcTicketRegistry());
    private final WebRtcSignalingHandler handler = new WebRtcSignalingHandler(sessions, tickets, JsonMapper.builder().build());
    private final String id = sessions.crearSesion(ProtocolType.DOMESTICO, true);

    @AfterEach
    void cleanup() { sessions.eliminarSesion(id); }

    @Test
    void delayedCloseOfReplacedBrowserDoesNotDisconnectTheNewPeer() throws Exception {
        List<String> browserMessages = new ArrayList<>();
        List<String> producerMessages = new ArrayList<>();
        WebSocketSession oldBrowser = connect(false, false, browserMessages);
        WebSocketSession producer = connect(true, false, producerMessages);
        producerMessages.clear();
        WebSocketSession replacement = connect(false, false, new ArrayList<>());

        handler.afterConnectionClosed(oldBrowser, CloseStatus.NORMAL);

        assertTrue(replacement.isOpen());
        assertEquals(List.of("{\"type\":\"peer-ready\"}"), producerMessages);
        handler.handleTextMessage(replacement, offer());
        assertTrue(producerMessages.get(1).contains("\"offer\""));
        assertTrue(producer.isOpen());
    }

    @Test
    void replacedSocketCannotRelayAnInFlightOffer() throws Exception {
        WebSocketSession oldBrowser = connect(false, false, new ArrayList<>());
        List<String> messages = new ArrayList<>();
        connect(true, false, messages);
        connect(false, false, new ArrayList<>());
        messages.clear();

        handler.handleTextMessage(oldBrowser, offer());

        assertTrue(messages.isEmpty(), "only the currently registered peer may signal");
    }

    @Test
    void nativeSendReturnsWithoutWaitingAndPreservesSignalingOrder() throws Exception {
        connect(false, false, new ArrayList<>());
        List<String> sent = new ArrayList<>();
        List<SendHandler> callbacks = new ArrayList<>();
        NativeWebSocketSession socket = (NativeWebSocketSession) socket(true, true, new ArrayList<>());
        jakarta.websocket.Session nativeSession = mock(jakarta.websocket.Session.class);
        RemoteEndpoint.Async async = mock(RemoteEndpoint.Async.class);
        when(socket.getNativeSession(jakarta.websocket.Session.class)).thenReturn(nativeSession);
        when(nativeSession.isOpen()).thenReturn(true);
        when(nativeSession.getAsyncRemote()).thenReturn(async);
        doAnswer(call -> {
            sent.add(call.getArgument(0));
            callbacks.add(call.getArgument(1));
            return null;
        }).when(async).sendText(anyString(), any(SendHandler.class));
        handler.afterConnectionEstablished(socket);
        assertEquals(1, sent.size());
        WebSocketSession browser = connect(false, false, new ArrayList<>());
        handler.handleTextMessage(browser, offer());
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"candidate\",\"candidate\":\"ice-1\"}"));
        assertEquals(1, sent.size(), "one async send at a time while the callback is pending");
        callbacks.get(0).onResult(new SendResult());
        callbacks.get(1).onResult(new SendResult());
        callbacks.get(2).onResult(new SendResult());
        assertTrue(sent.get(2).contains("\"offer\""));
        assertTrue(sent.get(3).contains("ice-1"));
        verify(socket, never()).sendMessage(any());
        verify(async, atLeastOnce()).setSendTimeout(3000L);
    }

    @Test
    void expiredSessionCannotContinueSignaling() throws Exception {
        WebSocketSession browser = connect(false, false, new ArrayList<>());
        List<String> messages = new ArrayList<>();
        connect(true, false, messages);
        messages.clear();
        sessions.getSesion(id).expirar();

        handler.handleTextMessage(browser, offer());

        assertFalse(messages.stream().anyMatch(value -> value.contains("\"offer\"")));
        assertFalse(browser.isOpen());
    }

    @Test
    void blockedAsyncPeerHasABoundedQueueAndLateCallbackCannotResurrectIt() throws Exception {
        WebSocketSession browser = connect(false, false, new ArrayList<>());
        NativeWebSocketSession producer = (NativeWebSocketSession) socket(true, true, new ArrayList<>());
        jakarta.websocket.Session nativeSession = mock(jakarta.websocket.Session.class);
        RemoteEndpoint.Async async = mock(RemoteEndpoint.Async.class);
        List<SendHandler> callbacks = new ArrayList<>();
        when(producer.getNativeSession(jakarta.websocket.Session.class)).thenReturn(nativeSession);
        when(nativeSession.getAsyncRemote()).thenReturn(async);
        doAnswer(call -> { callbacks.add(call.getArgument(1)); return null; })
            .when(async).sendText(anyString(), any(SendHandler.class));
        handler.afterConnectionEstablished(producer);
        for (int index = 0; index < 64; index++) {
            handler.handleTextMessage(browser, new TextMessage("{\"type\":\"candidate\",\"candidate\":\"ice\"}"));
        }
        assertFalse(producer.isOpen());
        verify(producer).close(argThat(status -> status.getCode() == 1013));
        callbacks.get(0).onResult(new SendResult());
        assertEquals(1, callbacks.size(), "no pending message may be sent after overflow");
    }

    @Test
    void malformedCandidateTypesAreRejectedWithoutBeingCoerced() throws Exception {
        WebSocketSession browser = connect(false, false, new ArrayList<>());
        List<String> messages = new ArrayList<>();
        connect(true, false, messages);
        messages.clear();
        handler.handleTextMessage(browser,
            new TextMessage("{\"type\":\"candidate\",\"candidate\":12,\"sdpMLineIndex\":0.5}"));
        assertFalse(browser.isOpen());
        assertFalse(messages.stream().anyMatch(value -> value.contains("\"candidate\"")));
    }

    @Test
    void replacementDropsSignalsStillQueuedFromTheOldPeer() throws Exception {
        WebSocketSession oldBrowser = connect(false, false, new ArrayList<>());
        NativeWebSocketSession producer = (NativeWebSocketSession) socket(true, true, new ArrayList<>());
        jakarta.websocket.Session nativeSession = mock(jakarta.websocket.Session.class);
        RemoteEndpoint.Async async = mock(RemoteEndpoint.Async.class);
        List<String> sent = new ArrayList<>();
        List<SendHandler> callbacks = new ArrayList<>();
        when(producer.getNativeSession(jakarta.websocket.Session.class)).thenReturn(nativeSession);
        when(nativeSession.getAsyncRemote()).thenReturn(async);
        doAnswer(call -> { sent.add(call.getArgument(0)); callbacks.add(call.getArgument(1)); return null; })
            .when(async).sendText(anyString(), any(SendHandler.class));
        handler.afterConnectionEstablished(producer);
        handler.handleTextMessage(oldBrowser, new TextMessage("{\"type\":\"candidate\",\"candidate\":\"old-ice\"}"));
        WebSocketSession browser = connect(false, false, new ArrayList<>());
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"candidate\",\"candidate\":\"new-ice\"}"));
        for (int index = 0; index < callbacks.size(); index++) callbacks.get(index).onResult(new SendResult());
        assertFalse(sent.stream().anyMatch(value -> value.contains("old-ice")));
        assertTrue(sent.stream().anyMatch(value -> value.contains("new-ice")));
    }

    private WebSocketSession connect(boolean producer, boolean nativeAdapter, List<String> sent) throws Exception {
        WebSocketSession socket = socket(producer, nativeAdapter, sent);
        handler.afterConnectionEstablished(socket);
        return socket;
    }

    private WebSocketSession socket(boolean producer, boolean nativeAdapter, List<String> sent) throws Exception {
        WebSocketSession socket = nativeAdapter ? mock(NativeWebSocketSession.class) : mock(WebSocketSession.class);
        String token = producer ? sessions.getDeviceToken(id) : sessions.getOwnerToken(id);
        var ticket = tickets.issue(id, token);
        assertEquals(WebRtcTicketService.Outcome.ISSUED, ticket.outcome());
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/webrtc/" + id + "?ticket=" + ticket.issued().value()));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        AtomicBoolean open = new AtomicBoolean(true);
        when(socket.isOpen()).thenAnswer(call -> open.get());
        doAnswer(call -> { open.set(false); return null; }).when(socket).close(any(CloseStatus.class));
        doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
            .when(socket).sendMessage(any());
        return socket;
    }

    private static TextMessage offer() {
        return new TextMessage("{\"type\":\"offer\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0\\r\\n\"}}");
    }
}
