package com.handwash.security.admin;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Login, logout and password change of the local ADMIN accounts (on-premises only). */
@RestController
@Profile("onprem")
public class AdminAuthController {
    public record LoginRequest(String username, String password, String totp) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}

    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) { this.auth = auth; }

    @PostMapping("/api/v1/auth/admin/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest request, HttpServletRequest http) {
        if (request == null) return ResponseEntity.status(401).body(ApiErrorResponse.of("CREDENCIALES_INVALIDAS"));
        AdminAuthService.LoginOutcome outcome = auth.login(request.username(), request.password(), request.totp(), ClientAddress.of(http));
        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok(Map.of("token", outcome.token(), "expiresAtEpochMs", outcome.expiresAtEpochMs(),
                "username", outcome.username()));
            case ENROLLMENT_REQUIRED -> ResponseEntity.ok(Map.of("enrollmentRequired", true, "username", outcome.username(),
                "totpSecret", outcome.totpSecret(), "otpauthUri", outcome.otpauthUri()));
            case LOCKED -> ResponseEntity.status(423).body(Map.of("error", "CUENTA_BLOQUEADA",
                "retryAfterSeconds", outcome.retryAfterSeconds()));
            case INVALID -> ResponseEntity.status(401).body(ApiErrorResponse.of("CREDENCIALES_INVALIDAS"));
        };
    }

    @PostMapping("/api/v1/auth/admin/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) auth.logout(authorization.substring(7).trim());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/account/password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal Jwt admin, @RequestBody(required = false) PasswordRequest request) {
        if (admin == null || !admin.getSubject().startsWith("admin:")) return ResponseEntity.status(403).build();
        if (request == null) return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        try {
            long accountId = Long.parseLong(admin.getSubject().substring("admin:".length()));
            return auth.changePassword(accountId, request.currentPassword(), request.newPassword())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(403).body(ApiErrorResponse.of("CONTRASENA_ACTUAL_INCORRECTA"));
        } catch (IllegalArgumentException weak) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("CONTRASENA_DEBIL"));
        }
    }
}
