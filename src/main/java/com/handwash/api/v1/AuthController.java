package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.DashboardLoginResponse;
import com.handwash.api.v1.dto.LoginRequest;
import com.handwash.api.v1.dto.LoginResponse;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionManager;
import com.handwash.security.SessionAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Account-less producer and read-only dashboard logins using a local session pairing code. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SessionAuthenticationService authenticationService;
    private final RequestRateLimiter rateLimiter;

    public AuthController(SessionAuthenticationService authenticationService, RequestRateLimiter rateLimiter) {
        this.authenticationService = authenticationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.allowPairing(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        }
        String code = request == null ? null : request.code();
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("code es obligatorio"));
        }
        final int protocolVersion;
        try {
            protocolVersion = parseProtocolVersion(request.producerProtocolVersion());
        } catch (IllegalArgumentException unsupported) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(unsupported.getMessage()));
        }

        SessionAuthenticationService.DeviceLogin result =
            authenticationService.loginWithPairingCode(code, protocolVersion);
        if (!result.successful()) {
            if (result.failure() == SessionAuthenticationService.Failure.INVALID_CODE) {
                return ResponseEntity.status(404).body(ApiErrorResponse.of("CODIGO_NO_VALIDO"));
            }
            if (result.failure() == SessionAuthenticationService.Failure.PRODUCER_VERSION_REQUIRED) {
                return ResponseEntity.status(409).body(ApiErrorResponse.producerVersionRequired());
            }
            return ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_TERMINADA"));
        }
        SessionManager.TokenIssue token = result.token();
        return ResponseEntity.ok(new LoginResponse(
            result.session().getSessionId(), result.session().getProtocolo().value(),
            "DEVICE", token.value(), "Bearer",
            Instant.ofEpochMilli(token.expiresAtEpochMs()).toString(), protocolVersion));
    }

    @PostMapping("/dashboard-login")
    public ResponseEntity<?> loginDashboard(
        @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.allowPairing(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        }
        String code = request == null ? null : request.code();
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("code es obligatorio"));
        }

        SessionAuthenticationService.DashboardLogin result =
            authenticationService.loginDashboardWithPairingCode(code);
        if (!result.successful()) {
            if (result.failure() == SessionAuthenticationService.Failure.INVALID_CODE) {
                return ResponseEntity.status(404).body(ApiErrorResponse.of("CODIGO_NO_VALIDO"));
            }
            return ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_TERMINADA"));
        }
        SessionManager.TokenIssue token = result.token();
        return ResponseEntity.ok(new DashboardLoginResponse(
            result.session().getSessionId(), result.session().getProtocolo().value(),
            "VIEWER", token.value(), "Bearer",
            Instant.ofEpochMilli(token.expiresAtEpochMs()).toString()));
    }

    private int parseProtocolVersion(String requested) {
        if (requested == null || requested.isBlank() || "1".equals(requested)) return 1;
        if ("2".equals(requested)) return 2;
        throw new IllegalArgumentException("producerProtocolVersion debe ser 1 o 2");
    }
}
