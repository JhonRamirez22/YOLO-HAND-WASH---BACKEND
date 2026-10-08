package com.handwash.websocket;

import com.handwash.agent.Notificador;
import com.handwash.agent.Receptor;
import com.handwash.model.SesionLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.security.WebSocketTicketRegistry;
import com.handwash.security.WebSocketTicketService;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HandWashWebSocketHandlerTest {
    @Test
    void rejectedSocketIsClosedAfterReleasingSessionMonitor() throws Exception {
        SessionManager sessions = new SessionManager(new Receptor());
        String sessionId = sessions.crearSesion(TipoProtocolo.DOMESTICO);
        SesionLavado sessionState = sessions.getSesion(sessionId);
        Notificador notifier = new Notificador(sessions);
        WebSocketTicketService tickets = new WebSocketTicketService(
            sessions, new WebSocketTicketRegistry());
        HandWashWebSocketHandler handler = new HandWashWebSocketHandler(sessions, notifier, tickets);
        AtomicBoolean closedWhileSessionLocked = new AtomicBoolean(true);
        AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        URI uri = URI.create("ws://localhost/ws/" + sessionId);
        WebSocketSession socket = (WebSocketSession) Proxy.newProxyInstance(
            WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "getUri" -> uri;
                case "close" -> {
                    closedWhileSessionLocked.set(Thread.holdsLock(sessionState));
                    closeStatus.set((CloseStatus) args[0]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

        try {
            handler.afterConnectionEstablished(socket);

            assertFalse(closedWhileSessionLocked.get(),
                "socket close can block and must not hold the session monitor");
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), closeStatus.get().getCode());
        } finally {
            ReflectionTestUtils.invokeMethod(notifier, "cerrarEnviadores");
            sessions.eliminarSesion(sessionId);
        }
    }
}
