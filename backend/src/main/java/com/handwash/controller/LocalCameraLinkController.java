package com.handwash.controller;

import com.handwash.api.v1.dto.DashboardLoginResponse;
import com.handwash.service.ContinuityCameraLinkService;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/** Code-free viewer pairing for the same-origin, loopback demonstration UI only. */
@RestController
@Profile("(station-demo | diagnostics) & !station")
public final class LocalCameraLinkController {
    private final SessionManager sessions;
    private final ContinuityCameraLinkService camera;
    private final RequestRateLimiter limiter;

    public LocalCameraLinkController(SessionManager sessions, ContinuityCameraLinkService camera,
                                     RequestRateLimiter limiter) {
        this.sessions = sessions;
        this.camera = camera;
        this.limiter = limiter;
    }

    public record LinkResponse(String status, String message, DashboardLoginResponse session) {}

    @PostMapping(value = "/api/v1/camera/link", consumes = "application/json")
    public ResponseEntity<LinkResponse> link(HttpServletRequest request) {
        if (!trustedLocalPage(request)) return reply(403, "forbidden", "Abre la web local desde este launcher.");
        if (!limiter.allowPairing(request.getRemoteAddr())) {
            return reply(429, "busy", "Demasiados intentos de enlace. Espera un minuto.");
        }
        var session = sessions.getSesionActiva();
        if (session == null) return reply(503, "unavailable", "El capturador aún no está listo; espera al arranque de YOLO y vuelve a enlazar.");
        String id = session.getSessionId();
        var result = camera.link(id);
        if (!result.status().equals("connected") && !result.status().equals("reconnecting")) {
            return reply(result.status().equals("busy") ? 429 : 503, result.status(), result.message());
        }
        var token = sessions.obtenerTokenDashboardLocal(id);
        if (token == null) return reply(409, "conflict", "La sesión terminó durante el enlace. Vuelve a intentarlo.");
        var viewer = new DashboardLoginResponse(id, session.getProtocolo().value(), "VIEWER",
            token.value(), "Bearer", Instant.ofEpochMilli(token.expiresAtEpochMs()).toString());
        return ResponseEntity.status(result.status().equals("connected") ? 200 : 202)
            .body(new LinkResponse(result.status(), result.message(), viewer));
    }

    private static ResponseEntity<LinkResponse> reply(int code, String status, String message) {
        return ResponseEntity.status(code).body(new LinkResponse(status, message, null));
    }

    static boolean trustedLocalPage(HttpServletRequest request) {
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
            || !Set.of("localhost", "127.0.0.1").contains(request.getServerName())
            || request.getServerPort() != 8080 || !"http".equals(request.getScheme())
            || !"1".equals(request.getHeader("X-Handwash-Local-UI"))) return false;
        String expected = "http://" + request.getServerName() + ":8080";
        if (!expected.equals(request.getHeader("Origin"))) return false;
        String site = request.getHeader("Sec-Fetch-Site");
        return site == null || "same-origin".equals(site);
    }
}
