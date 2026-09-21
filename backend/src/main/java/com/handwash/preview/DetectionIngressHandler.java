package com.handwash.preview;

import com.handwash.api.v1.dto.DetectionRequest;
import com.handwash.api.v1.mapper.DetectionRequestMapper;
import com.handwash.model.DetectionEvent;
import com.handwash.observer.DetectionPipelineException;
import com.handwash.service.IncompatibleDetectionModeException;
import com.handwash.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * The RTX PC reports its detections over one persistent socket instead of one HTTP request per event: no round trip
 * per observation, so the backend decides the steps as fast as the PC produces them. It is the same processing as
 * {@code POST /deteccion} ({@link SessionManager#procesarDeteccionHttp}); the HTTP route stays as the fallback.
 *
 * <p>Protocol: the first message is {@code {"authToken": "<session device token>"}}; every later message is a
 * detection request. Accepted and filtered events are silent; anything else gets {@code {"status": N, "reason": ...}}
 * so the producer can react (superseded epoch, finished session).</p>
 */
@Component
public final class DetectionIngressHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DetectionIngressHandler.class);
    private static final String TOKEN = "ingressToken";
    private static final int MAX_MESSAGE_CHARS = 64 * 1024;

    private final SessionManager sessions;
    private final DetectionRequestMapper mapper;
    private final ObjectMapper json;

    public DetectionIngressHandler(SessionManager sessions, DetectionRequestMapper mapper, ObjectMapper json) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        socket.setTextMessageSizeLimit(MAX_MESSAGE_CHARS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws IOException {
        Object token = socket.getAttributes().get(TOKEN);
        if (token == null) {
            authenticate(socket, message.getPayload());
            return;
        }
        long ingressAt = System.nanoTime();
        final DetectionRequest request;
        try {
            request = json.readValue(message.getPayload(), DetectionRequest.class);
        } catch (RuntimeException malformed) {
            log.debug("Detección malformada ignorada");
            return;
        }
        DetectionEvent event = mapper.toDomain(request);
        if (event != null) event.setServerIngressAtMonotonicNanos(ingressAt);
        SessionManager.DetectionResult result;
        try {
            result = sessions.procesarDeteccionHttp(event, (String) token);
        } catch (IncompatibleDetectionModeException conflict) {
            reply(socket, 409, "MODO_DETECCION_INCOMPATIBLE");
            return;
        } catch (DetectionPipelineException failure) {
            reply(socket, 500, "ERROR_PROCESAMIENTO");
            return;
        }
        switch (result.outcome()) {
            case ACCEPTED, FILTERED -> { /* silent: the producer needs no answer */ }
            case NOT_FOUND -> reply(socket, 404, "NOT_FOUND");
            case UNAUTHORIZED -> reply(socket, 401, "ACCESO_NO_AUTORIZADO");
            case TERMINAL -> reply(socket, 409, "SESION_TERMINADA");
            case INVALID -> reply(socket, 400, result.detail());
            case TRANSPORT_REJECTED -> reply(socket, 422,
                result.rejectionReason() == null ? "RECHAZADO" : result.rejectionReason().name());
        }
    }

    private void authenticate(WebSocketSession socket, String payload) throws IOException {
        String sessionId = pathSessionId(socket);
        String candidate = null;
        try {
            JsonNode node = json.readTree(payload);
            if (node.path("authToken").isTextual()) candidate = node.path("authToken").asText();
        } catch (RuntimeException malformed) {
            candidate = null;
        }
        SessionManager.AccessInfo access = sessionId == null || candidate == null ? null : sessions.autenticar(sessionId, candidate);
        boolean producer = access != null && (access.role() == SessionManager.AccessRole.DEVICE
            || access.role() == SessionManager.AccessRole.OWNER);
        if (!producer) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Credencial de sesión inválida"));
            return;
        }
        socket.getAttributes().put(TOKEN, candidate);
    }

    private void reply(WebSocketSession socket, int status, String reason) {
        try {
            synchronized (socket) {
                if (socket.isOpen()) {
                    socket.sendMessage(new TextMessage(json.writeValueAsString(
                        java.util.Map.of("status", status, "reason", reason == null ? "" : reason))));
                }
            }
        } catch (IOException | RuntimeException error) {
            log.debug("No se pudo responder al productor: {}", error.getClass().getSimpleName());
        }
    }

    private static String pathSessionId(WebSocketSession socket) {
        if (socket.getUri() == null) return null;
        String[] pieces = socket.getUri().getPath().split("/");
        return pieces.length > 0 ? pieces[pieces.length - 1] : null;
    }
}
