package com.handwash.websocket;

import com.handwash.agent.Notificador;
import com.handwash.model.SesionLavado;
import com.handwash.service.SessionManager;
import com.handwash.security.WebSocketTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class HandWashWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(HandWashWebSocketHandler.class);
    private final SessionManager sessionManager;
    private final Notificador notificador;
    private final WebSocketTicketService ticketService;

    public HandWashWebSocketHandler(SessionManager sessionManager, Notificador notificador,
                                    WebSocketTicketService ticketService) {
        this.sessionManager = sessionManager;
        this.notificador = notificador;
        this.ticketService = ticketService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        SesionLavado sesion = sessionId == null ? null : sessionManager.getSesion(sessionId);
        if (sesion == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Sesion no activa"));
            return;
        }
        boolean autorizado;
        boolean registrado = false;
        synchronized (sesion) {
            SessionManager.AccessInfo access = ticketService.consume(sessionId, extractTicket(session));
            autorizado = sessionManager.getSesion(sessionId) == sesion && access != null
                && sessionManager.credencialVigente(sessionId, access);
            if (autorizado) {
                registrado = notificador.registrarSesion(sessionId, session, access);
            }
        }
        // Closing a rejected socket can perform network I/O. Keep it outside
        // the session monitor, which also serializes camera events and expiry.
        if (!autorizado) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Sesion inexistente o acceso no autorizado"));
            return;
        }
        if (!registrado) {
            session.close(new CloseStatus(1013, "Límite de conexiones WebSocket"));
            return;
        }
        // Do not hold the session lock during network I/O; a slow dashboard
        // must not stall YOLO detections or session expiration.
        notificador.enviarInstantanea(sessionId, sesion, session);
        log.info("Dashboard conectado por WebSocket.");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.close(CloseStatus.NOT_ACCEPTABLE.withReason("WebSocket de salida; envía detecciones por REST"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            notificador.eliminarSesion(sessionId, session);
            log.info("Dashboard desconectado por WebSocket.");
        }
    }

    private String extractSessionId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String uri = session.getUri().getPath();
        String[] parts = uri.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    private String extractTicket(WebSocketSession session) {
        if (session.getUri() == null) return null;
        return UriComponentsBuilder.fromUri(session.getUri()).build()
            .getQueryParams().getFirst("ticket");
    }

}
