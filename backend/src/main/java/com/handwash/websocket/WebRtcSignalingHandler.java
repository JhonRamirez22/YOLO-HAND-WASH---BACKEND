package com.handwash.websocket;

import com.handwash.model.WashSession;
import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Relays only bounded WebRTC offer/answer/ICE signaling; media never enters Java. */
@Component
public final class WebRtcSignalingHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_CHARS = 256 * 1024;
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final int MAX_PENDING_CHARS = 512 * 1024;
    private static final long SEND_TIMEOUT_MS = 3_000L;
    private static final Logger log = LoggerFactory.getLogger(WebRtcSignalingHandler.class);
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SessionManager sessions;
    private final WebRtcTicketService tickets;
    private final ObjectMapper json;

    public WebRtcSignalingHandler(SessionManager sessions, WebRtcTicketService tickets,
                                  ObjectMapper json) {
        this.sessions = sessions;
        this.tickets = tickets;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String sessionId = pathSessionId(socket);
        WashSession active = sessionId == null ? null : sessions.getSesion(sessionId);
        WebRtcTicketRegistry.Grant grant = sessionId == null ? null
            : tickets.consume(sessionId, queryTicket(socket));
        if (active == null || grant == null || !activeSession(sessionId)) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Ticket WebRTC inválido o vencido"));
            return;
        }
        socket.getAttributes().put("sessionId", sessionId);
        socket.getAttributes().put("peerRole", grant.role());
        log.info("Señalización WebRTC: se unió {} a la sesión {}", grant.role(), sessionId.substring(0, 8));
        socket.setTextMessageSizeLimit(MAX_MESSAGE_CHARS);
        Peer peer = new Peer(sessionId, socket, grant);
        socket.getAttributes().put("signalingPeer", peer);
        Peer[] previousAndOther = new Peer[2];
        // Registration and empty-room removal share the map's atomic operation;
        // a concurrent disconnect must not leave a new peer in a detached room.
        rooms.compute(sessionId, (id, current) -> {
            Room room = current == null ? new Room() : current;
            synchronized (room) {
                if (grant.role() == WebRtcTicketRegistry.PeerRole.BROWSER) {
                    previousAndOther[0] = room.browser;
                    room.browser = peer;
                    previousAndOther[1] = room.producer;
                } else {
                    previousAndOther[0] = room.producer;
                    room.producer = peer;
                    previousAndOther[1] = room.browser;
                }
            }
            return room;
        });
        if (previousAndOther[0] != null) previousAndOther[0].close(CloseStatus.NORMAL);
        Peer other = previousAndOther[1];
        if (other != null && other.socket.isOpen()) {
            send(peer, json.createObjectNode().put("type", "peer-ready"));
            send(other, json.createObjectNode().put("type", "peer-ready"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        Peer peer = peer(socket);
        if (peer == null || !peer.authorized()) {
            close(socket, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (message.getPayloadLength() > MAX_MESSAGE_CHARS) {
            peer.close(new CloseStatus(1009, "Mensaje de señalización demasiado grande"));
            return;
        }
        String sessionId = (String) socket.getAttributes().get("sessionId");
        Object roleValue = socket.getAttributes().get("peerRole");
        if (sessionId == null || !(roleValue instanceof WebRtcTicketRegistry.PeerRole role)) {
            peer.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        JsonNode incoming;
        try { incoming = json.readTree(message.getPayload()); }
        catch (RuntimeException malformed) { peer.close(CloseStatus.BAD_DATA); return; }
        ObjectNode safe = sanitize(incoming, role);
        if (safe == null) { peer.close(CloseStatus.POLICY_VIOLATION); return; }
        Room room = rooms.get(sessionId);
        Peer other = room == null ? null : room.other(peer);
        if (other == null || !other.socket.isOpen()) {
            peer.close(new CloseStatus(1013, "Par remoto desconectado"));
            return;
        }
        other.enqueue(json.writeValueAsString(safe), peer);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Peer disconnected = peer(socket);
        if (disconnected == null) return;
        disconnected.discard();
        Peer[] remaining = new Peer[1];
        rooms.computeIfPresent(disconnected.sessionId, (id, room) -> {
            synchronized (room) {
                if (room.browser == disconnected) { room.browser = null; remaining[0] = room.producer; }
                else if (room.producer == disconnected) { room.producer = null; remaining[0] = room.browser; }
                else return room; // A replaced peer cannot announce that the new peer left.
                return room.browser == null && room.producer == null ? null : room;
            }
        });
        if (remaining[0] != null) send(remaining[0], json.createObjectNode().put("type", "peer-left"));
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable error) {
        close(socket, CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Scheduled(fixedDelay = 1_000L)
    public void closeUnusablePeers() {
        for (Room room : rooms.values()) {
            Peer browser;
            Peer producer;
            synchronized (room) { browser = room.browser; producer = room.producer; }
            for (Peer peer : new Peer[] {browser, producer}) {
                if (peer != null && (!peer.authorized() || peer.sendTimedOut())) {
                    peer.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Enlace vencido o cliente lento"));
                }
            }
        }
    }

    private ObjectNode sanitize(JsonNode node, WebRtcTicketRegistry.PeerRole role) {
        if (node == null || !node.isObject()) return null;
        if (!node.path("type").isTextual()) return null;
        String type = node.path("type").asText("");
        ObjectNode clean = json.createObjectNode().put("type", type);
        if ("offer".equals(type) && role == WebRtcTicketRegistry.PeerRole.BROWSER
            || "answer".equals(type) && role == WebRtcTicketRegistry.PeerRole.RTX_PRODUCER) {
            JsonNode sdp = node.path("sdp");
            if (!sdp.isObject() || !sdp.path("type").isTextual() || !sdp.path("sdp").isTextual()) return null;
            String descriptionType = sdp.path("type").asText("");
            String body = sdp.path("sdp").asText("");
            if (!type.equals(descriptionType) || body.isBlank() || body.length() > 200_000) return null;
            ObjectNode desc = json.createObjectNode().put("type", descriptionType).put("sdp", body);
            return clean.set("sdp", desc);
        }
        if ("candidate".equals(type)) {
            if (!node.path("candidate").isTextual()) return null;
            String candidate = node.path("candidate").asText("");
            if (candidate.length() > 4_096) return null;
            clean.put("candidate", candidate);
            if (node.hasNonNull("sdpMid")) {
                if (!node.path("sdpMid").isTextual()) return null;
                String mid = node.path("sdpMid").asText();
                if (mid.length() > 32) return null;
                clean.put("sdpMid", mid);
            }
            if (node.hasNonNull("sdpMLineIndex")) {
                if (!node.path("sdpMLineIndex").isIntegralNumber()
                    || !node.path("sdpMLineIndex").canConvertToInt()) return null;
                int line = node.path("sdpMLineIndex").asInt(-1);
                if (line < 0 || line > 128) return null;
                clean.put("sdpMLineIndex", line);
            }
            return clean;
        }
        return null;
    }

    private void send(Peer peer, JsonNode payload) {
        peer.enqueue(json.writeValueAsString(payload), null);
    }

    private Peer peer(WebSocketSession socket) {
        Object value = socket.getAttributes().get("signalingPeer");
        return value instanceof Peer peer && peer.socket == socket ? peer : null;
    }

    private boolean activeSession(String id) {
        WashSession session = sessions.getSesion(id);
        if (session == null) return false;
        synchronized (session) {
            return sessions.getSesion(id) == session
                && session.getEstadoSesion() != com.handwash.model.SessionStatus.EXPIRADA
                && session.getEstadoSesion() != com.handwash.model.SessionStatus.COMPLETADA;
        }
    }

    private void close(WebSocketSession socket, CloseStatus status) {
        Peer peer = peer(socket);
        if (peer != null) peer.close(status);
        else {
            try { socket.close(status); }
            catch (IOException | RuntimeException error) {
                log.debug("No se pudo cerrar señalización: {}", error.getClass().getSimpleName());
            }
        }
    }

    private static String pathSessionId(WebSocketSession socket) {
        if (socket.getUri() == null) return null;
        String[] pieces = socket.getUri().getPath().split("/");
        return pieces.length >= 3 && "webrtc".equals(pieces[pieces.length - 2])
            ? pieces[pieces.length - 1] : null;
    }

    private static String queryTicket(WebSocketSession socket) {
        if (socket.getUri() == null) return null;
        return UriComponentsBuilder.fromUri(socket.getUri()).build().getQueryParams().getFirst("ticket");
    }

    private final class Peer {
        private final String sessionId;
        private final WebSocketSession socket;
        private final WebRtcTicketRegistry.Grant grant;
        private final ArrayDeque<QueuedSignal> pending = new ArrayDeque<>();
        private int retainedChars;
        private int inFlightChars;
        private boolean sending;
        private boolean closed;
        private long sendStarted;

        private Peer(String id, WebSocketSession socket, WebRtcTicketRegistry.Grant grant) {
            this.sessionId = id;
            this.socket = socket;
            this.grant = grant;
        }

        private boolean authorized() {
            Room room = rooms.get(sessionId);
            return socket.isOpen() && room != null && room.contains(this)
                && activeSession(sessionId) && sessions.credencialVigente(sessionId, grant.access());
        }

        private void enqueue(String payload, Peer source) {
            boolean full;
            synchronized (this) {
                if (closed) return;
                full = pending.size() + (sending ? 1 : 0) >= MAX_PENDING_MESSAGES
                    || payload.length() > MAX_PENDING_CHARS - retainedChars;
                if (!full) { pending.addLast(new QueuedSignal(payload, source)); retainedChars += payload.length(); }
            }
            if (full) close(new CloseStatus(1013, "Cola de señalización saturada"));
            else drain();
        }

        private void drain() {
            QueuedSignal signal;
            synchronized (this) {
                if (closed || sending || pending.isEmpty()) return;
                signal = pending.removeFirst();
                sending = true;
                inFlightChars = signal.payload().length();
                sendStarted = System.nanoTime();
            }
            // Pending SDP/ICE belongs to the original sender, not to its replacement.
            if (signal.source() != null && !signal.source().authorized()) { complete(); return; }
            if (!authorized()) { close(CloseStatus.POLICY_VIOLATION); return; }
            String payload = signal.payload();
            try {
                jakarta.websocket.Session nativeSession = socket instanceof NativeWebSocketSession wrapper
                    ? wrapper.getNativeSession(jakarta.websocket.Session.class) : null;
                if (nativeSession != null) {
                    nativeSession.getAsyncRemote().setSendTimeout(SEND_TIMEOUT_MS);
                    nativeSession.getAsyncRemote().sendText(payload, result -> {
                        if (result.isOK()) complete();
                        else close(CloseStatus.SESSION_NOT_RELIABLE);
                    });
                } else {
                    // Compatibility adapters only; production Tomcat uses its async endpoint.
                    socket.sendMessage(new TextMessage(payload));
                    complete();
                }
            } catch (IOException | RuntimeException error) {
                log.debug("Falló el envío de señalización: {}", error.getClass().getSimpleName());
                close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        }

        private void complete() {
            synchronized (this) {
                if (closed) return;
                retainedChars -= inFlightChars;
                inFlightChars = 0;
                sending = false;
            }
            drain();
        }

        private synchronized boolean sendTimedOut() {
            return sending && System.nanoTime() - sendStarted >= TimeUnit.MILLISECONDS.toNanos(SEND_TIMEOUT_MS);
        }

        private synchronized void discard() {
            closed = true;
            pending.clear();
            retainedChars = 0;
            sending = false;
        }

        private void close(CloseStatus status) {
            discard();
            afterConnectionClosed(socket, status);
            try { if (socket.isOpen()) socket.close(status); }
            catch (IOException | RuntimeException error) {
                log.debug("No se pudo cerrar señalización: {}", error.getClass().getSimpleName());
            }
        }
    }

    private record QueuedSignal(String payload, Peer source) {}

    private static final class Room {
        private Peer browser;
        private Peer producer;
        private synchronized boolean contains(Peer peer) { return browser == peer || producer == peer; }
        private synchronized Peer other(Peer self) {
            if (browser == self) return producer;
            if (producer == self) return browser;
            return null;
        }
    }
}
