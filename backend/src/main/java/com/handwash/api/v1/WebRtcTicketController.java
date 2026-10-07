package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.WebRtcTicketResponse;
import com.handwash.webrtc.IceServerProvider;
import com.handwash.security.SessionTokenResolver;
import com.handwash.security.WebRtcTicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/session")
public final class WebRtcTicketController {
    private final WebRtcTicketService service;
    private final SessionTokenResolver tokens;
    private final IceServerProvider iceServers;

    public WebRtcTicketController(WebRtcTicketService service, SessionTokenResolver tokens,
                                  IceServerProvider iceServers) {
        this.service = service;
        this.tokens = tokens;
        this.iceServers = iceServers;
    }

    @PostMapping("/{sessionId}/webrtc-ticket")
    public ResponseEntity<?> issue(@PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        WebRtcTicketService.Result result = service.issue(sessionId,
            tokens.resolve(authorization, sessionToken));
        return switch (result.outcome()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case UNAUTHORIZED -> ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            case CAPACITY -> ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_TICKETS_WEBRTC"));
            case ISSUED -> ResponseEntity.ok(new WebRtcTicketResponse(result.issued().value(),
                result.role().name(), Instant.ofEpochMilli(result.issued().expiresAtMs()).toString(),
                iceServers.servers(sessionId, result.role())));
        };
    }
}
