package com.handwash.preview;

import com.handwash.model.WashSession;
import com.handwash.security.WebSocketTicketService;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel watches its session: the phone's frames in full quality (binary, 4-byte sequence + JPEG) and the PC's
 * overlay with the hand boxes and the step (text JSON). A panel that cannot keep up never queues: newest wins.
 */
@Component
public final class PreviewSocketHandler extends BinaryWebSocketHandler {

    private final SessionManager sessions;
    private final WebSocketTicketService tickets;
    private final CameraFrameHub hub;
    private final Map<String, Runnable> unsubscribeBySocket = new ConcurrentHashMap<>();

    public PreviewSocketHandler(SessionManager sessions, WebSocketTicketService tickets, CameraFrameHub hub) {
        this.sessions = sessions;
        this.tickets = tickets;
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = pathSessionId(socket);
        WashSession session = sessionId == null ? null : sessions.getSesion(sessionId);
        SessionManager.AccessInfo access = session == null ? null : tickets.consume(sessionId, queryTicket(socket));
        if (access == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Sesión inexistente o acceso no autorizado"));
            return;
        }
        try {
            unsubscribeBySocket.put(socket.getId(), hub.subscribe(sessionId, CameraFrameHub.Role.VIEWER, new LatestWinsSocket(socket)));
        } catch (IllegalStateException full) {
            socket.close(new CloseStatus(1013, "Demasiados paneles viendo la sesión"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Runnable unsubscribe = unsubscribeBySocket.remove(socket.getId());
        if (unsubscribe != null) unsubscribe.run();
    }

    private static String pathSessionId(WebSocketSession socket) {
        if (socket.getUri() == null) return null;
        String[] pieces = socket.getUri().getPath().split("/");
        return pieces.length > 0 ? pieces[pieces.length - 1] : null;
    }

    private static String queryTicket(WebSocketSession socket) {
        return socket.getUri() == null ? null
            : UriComponentsBuilder.fromUri(socket.getUri()).build().getQueryParams().getFirst("ticket");
    }
}
