package com.handwash.preview;

import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The phone's side of the camera: it pushes JPEG frames (4-byte sequence + JPEG) over one persistent socket. Authenticated
 * by a camera ticket (the camera-only credential). There is one camera per session: a newer connection replaces the
 * previous one, which is closed.
 */
@Component
public final class CameraFrameIngressHandler extends BinaryWebSocketHandler {
    private static final String SESSION = "cameraIngressSession";
    private static final long MIN_FRAME_GAP_NANOS = 20_000_000L; // at most ~50 frames per second per camera

    private final WebRtcTicketService tickets;
    private final CameraFrameHub hub;
    private final Map<String, WebSocketSession> cameraBySession = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFrameAt = new ConcurrentHashMap<>();

    public CameraFrameIngressHandler(WebRtcTicketService tickets, CameraFrameHub hub) {
        this.tickets = tickets;
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = pathSessionId(socket);
        WebRtcTicketRegistry.Grant grant = sessionId == null ? null : tickets.consume(sessionId, queryTicket(socket));
        if (grant == null || grant.role() != WebRtcTicketRegistry.PeerRole.BROWSER) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Ticket inválido"));
            return;
        }
        socket.setBinaryMessageSizeLimit(CameraFrameHub.MAX_FRAME_BYTES);
        socket.getAttributes().put(SESSION, sessionId);
        WebSocketSession previous = cameraBySession.put(sessionId, socket);
        if (previous != null && previous != socket && previous.isOpen()) {
            try { previous.close(new CloseStatus(4000, "Cámara reemplazada")); } catch (IOException ignored) { /* gone */ }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        Object sessionId = socket.getAttributes().get(SESSION);
        if (!(sessionId instanceof String id) || cameraBySession.get(id) != socket) return;
        long now = System.nanoTime();
        Long last = lastFrameAt.put(socket.getId(), now);
        if (last != null && now - last < MIN_FRAME_GAP_NANOS) return;
        byte[] frame = new byte[message.getPayloadLength()];
        message.getPayload().get(frame);
        hub.publishFrame(id, frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        lastFrameAt.remove(socket.getId());
        Object sessionId = socket.getAttributes().get(SESSION);
        if (sessionId instanceof String id) cameraBySession.remove(id, socket);
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
