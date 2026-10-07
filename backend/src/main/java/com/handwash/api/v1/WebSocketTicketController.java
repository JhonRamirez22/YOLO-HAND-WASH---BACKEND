package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.WebSocketTicketResponse;
import com.handwash.security.SessionTokenResolver;
import com.handwash.security.WebSocketTicketService;
import com.handwash.security.WebSocketTicketRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/session")
public class WebSocketTicketController {
    private final WebSocketTicketService ticketService;
    private final SessionTokenResolver tokenResolver;

    public WebSocketTicketController(WebSocketTicketService ticketService,
                                     SessionTokenResolver tokenResolver) {
        this.ticketService = ticketService;
        this.tokenResolver = tokenResolver;
    }

    @PostMapping("/{sessionId}/websocket-ticket")
    public ResponseEntity<?> issue(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        WebSocketTicketService.IssueResult result = ticketService.issue(
            sessionId, tokenResolver.resolve(authorization, legacyToken));
        return switch (result.outcome()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            case CAPACITY -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorResponse.of("LIMITE_TICKETS_WEBSOCKET"));
            case ISSUED -> ResponseEntity.ok(new WebSocketTicketResponse(
                result.issue().value(), Instant.ofEpochMilli(result.issue().expiresAtEpochMs()).toString()));
        };
    }
}
