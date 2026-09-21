package com.handwash.preview;

import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The RTX PC's side of the camera: it receives the phone's frames (binary, newest first) and answers with its overlay
 * (text JSON with the hand boxes and the detected step) that only the panels receive. Authenticated by a WebRTC
 * producer ticket, like the PC's other channels.
 */
@Component
public final class CameraFrameEgressHandler extends TextWebSocketHandler {
    private static final String SESSION = "cameraEgressSession";
    private static final String LAST_HINT = "cameraEgressLastHint";

    private final WebRtcTicketService tickets;
    private final CameraFrameHub hub;
    private final SessionProgressSource progress;
    private final Map<String, Runnable> unsubscribeBySocket = new ConcurrentHashMap<>();

    public CameraFrameEgressHandler(WebRtcTicketService tickets, CameraFrameHub hub, SessionProgressSource progress) {
        this.tickets = tickets;
        this.hub = hub;
        this.progress = progress;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = pathSessionId(socket);
        WebRtcTicketRegistry.Grant grant = sessionId == null ? null : tickets.consume(sessionId, queryTicket(socket));
        if (grant == null || grant.role() != WebRtcTicketRegistry.PeerRole.RTX_PRODUCER) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Ticket inválido"));
            return;
        }
        socket.setTextMessageSizeLimit(CameraFrameHub.MAX_OVERLAY_CHARS);
        try {
            unsubscribeBySocket.put(socket.getId(), hub.subscribe(sessionId, CameraFrameHub.Role.PRODUCER, new LatestWinsSocket(socket)));
            socket.getAttributes().put(SESSION, sessionId);
        } catch (IllegalStateException alreadyConnected) {
            socket.close(new CloseStatus(1013, "Ya hay un PC conectado a esta sesión"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Object sessionId = socket.getAttributes().get(SESSION);
        if (!(sessionId instanceof String id)) return;
        SessionProgress now = progress.of(id);
        hub.publishOverlay(id, OverlayAuthority.withProgress(message.getPayload(), now));
        if (!now.equals(socket.getAttributes().put(LAST_HINT, now))) hub.publishHint(id, OverlayAuthority.hint(now));
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
