package com.handwash.controller;

import com.handwash.security.SessionTokenResolver;
import com.handwash.service.ContinuityCameraLinkService;
import com.handwash.service.SessionManager;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!station")
@RequestMapping({"/api/v1/session", "/api/session"})
public final class ContinuityCameraController {
    private final SessionManager sessions;
    private final SessionTokenResolver tokens;
    private final ContinuityCameraLinkService camera;

    public ContinuityCameraController(SessionManager sessions, SessionTokenResolver tokens,
                                      ContinuityCameraLinkService camera) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.camera = camera;
    }

    @PostMapping("/{sessionId}/camera/link")
    public ResponseEntity<?> link(@PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        if (sessions.getSesion(sessionId) == null) return ResponseEntity.notFound().build();
        String token = tokens.resolve(authorization, legacyToken);
        if (!sessions.tieneAccesoLectura(sessionId, token)) return ResponseEntity.status(401).build();
        var result = camera.link(sessionId);
        if (!sessions.tieneAccesoLectura(sessionId, token)) return ResponseEntity.status(401).build();
        int code = switch (result.status()) {
            case "connected" -> 200;
            case "reconnecting" -> 202;
            case "busy" -> 429;
            case "conflict" -> 409;
            default -> 503;
        };
        return ResponseEntity.status(code).body(result);
    }
}
