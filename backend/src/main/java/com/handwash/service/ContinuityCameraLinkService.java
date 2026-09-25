package com.handwash.service;

import com.handwash.model.SessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Local Adapter: requests camera discovery without rotating producer credentials. */
@Service
public final class ContinuityCameraLinkService {
    private static final URI LINK_URI = URI.create("http://127.0.0.1:8091/camera/link");
    private final SessionManager sessions;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public ContinuityCameraLinkService(SessionManager sessions, ObjectMapper mapper) {
        this(sessions, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    ContinuityCameraLinkService(SessionManager sessions, ObjectMapper mapper, HttpClient client) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.client = client;
    }

    public record Result(String status, String message) {}

    public Result link(String sessionId) {
        var session = sessions.getSesion(sessionId);
        if (session == null) return new Result("conflict", "No hay una sesión de cámara activa.");
        final String deviceToken;
        final String ownerToken;
        synchronized (session) {
            if (session.getEstadoSesion() == SessionStatus.COMPLETADA
                || session.getEstadoSesion() == SessionStatus.EXPIRADA) {
                return new Result("conflict", "La sesión terminó; inicia otra sesión.");
            }
            deviceToken = sessions.getDeviceToken(sessionId);
            ownerToken = sessions.getOwnerToken(sessionId);
        }
        String controlToken = deviceToken == null || deviceToken.isBlank() ? ownerToken : deviceToken;
        if (controlToken == null || controlToken.isBlank()) {
            return new Result("unavailable", "El capturador no está listo. Ejecuta scripts/start_handwash.sh.");
        }
        try {
            var response = sendLink(sessionId, controlToken);
            // A producer that created its own session still uses OWNER, not DEVICE.
            if (response.statusCode() == 403 && ownerToken != null && !ownerToken.equals(controlToken)) {
                response = sendLink(sessionId, ownerToken);
            }
            if (response.statusCode() == 429) return new Result("busy", "Ya se está enlazando el iPhone; espera unos segundos.");
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                return new Result("conflict", "El capturador pertenece a otra sesión o su credencial cambió.");
            }
            var status = mapper.readTree(response.body()).path("status").asText();
            return switch (status) {
                case "connected" -> new Result("connected", "iPhone enlazado: cámara de Continuidad conectada.");
                case "reconnecting" -> new Result("reconnecting", "Buscando el iPhone. Activa Cámara de Continuidad y autoriza su uso en macOS.");
                default -> new Result("unavailable", "El capturador devolvió una respuesta inválida.");
            };
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result("unavailable", "Se interrumpió el enlace con la cámara.");
        } catch (JacksonException error) {
            return new Result("unavailable", "El capturador devolvió una respuesta inválida.");
        } catch (IOException error) {
            return new Result("unavailable", "Capturador no disponible. Ejecuta scripts/start_handwash.sh.");
        }
    }

    private HttpResponse<String> sendLink(String sessionId, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(LINK_URI).timeout(Duration.ofSeconds(2))
            .header("Content-Type", "application/json")
            .header("X-Session-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("sessionId", sessionId))))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
