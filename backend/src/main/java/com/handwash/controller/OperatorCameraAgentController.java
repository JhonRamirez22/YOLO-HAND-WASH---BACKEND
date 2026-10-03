package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.camera.CameraAgentRegistry;
import com.handwash.service.StationOperatorAccessService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The operator console asks for the iPhone camera helper of its station and sees whether it is alive; the helper
 * (signed in with the same operator key) reports every few seconds and learns whether it must stream.
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/operator/camera-agent")
public class OperatorCameraAgentController {
    public record RequestBody(Boolean wanted) {}
    public record HeartbeatBody(String device, String state, String message) {}

    private final StationOperatorAccessService access;
    private final CameraAgentRegistry registry;

    public OperatorCameraAgentController(StationOperatorAccessService access, CameraAgentRegistry registry) {
        this.access = access;
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<?> view(@AuthenticationPrincipal Jwt operator) {
        String stationId = stationOf(operator);
        if (stationId == null) return forbidden();
        return ResponseEntity.ok(registry.view(stationId));
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(@AuthenticationPrincipal Jwt operator,
                                     @org.springframework.web.bind.annotation.RequestBody(required = false) RequestBody body) {
        String stationId = stationOf(operator);
        if (stationId == null) return forbidden();
        if (body == null || body.wanted() == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            registry.request(stationId, body.wanted());
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADAS_ESTACIONES"));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@AuthenticationPrincipal Jwt operator,
                                       @org.springframework.web.bind.annotation.RequestBody(required = false) HeartbeatBody body) {
        String stationId = stationOf(operator);
        if (stationId == null) return forbidden();
        if (body == null) return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        try {
            return ResponseEntity.ok(Map.of("wanted",
                registry.heartbeat(stationId, body.device(), body.state(), body.message())));
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADAS_ESTACIONES"));
        }
    }

    /** The station the caller operates, from its active grant (a console key, or a signed-in operator). */
    private String stationOf(Jwt operator) {
        if (operator == null || operator.getSubject() == null) return null;
        StationOperatorAccessService.GrantView grant = access.activeGrant(operator.getSubject());
        return grant == null ? null : grant.stationId();
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
    }
}
