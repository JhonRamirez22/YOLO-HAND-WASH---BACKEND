package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.enroll.ProducerEnrollmentService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;

/** ADMIN-only: see the PCs waiting to be paired and approve one for a station with a single click. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/producer-enrollments")
public class AdminProducerEnrollmentController {
    public record ApproveRequest(String stationId) {}

    private final ProducerEnrollmentService enrollments;
    private final ApiKeyService keys;

    public AdminProducerEnrollmentController(ProducerEnrollmentService enrollments, ApiKeyService keys) {
        this.enrollments = enrollments;
        this.keys = keys;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(enrollments.pending());
    }

    @PostMapping("/{userCode}/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Jwt admin, @PathVariable String userCode,
                                     @RequestBody(required = false) ApproveRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null || request.stationId() == null || request.stationId().isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            boolean approved = enrollments.approve(userCode, deviceName ->
                keys.issue(request.stationId(), deviceName, EnumSet.of(ApiKeyScope.PRODUCER), admin.getSubject()).key());
            return approved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        } catch (IllegalStateException limit) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("LIMITE_DE_KEYS_POR_PUESTO"));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("KEYS_NO_DISPONIBLES"));
        }
    }

    @DeleteMapping("/{userCode}")
    public ResponseEntity<?> reject(@AuthenticationPrincipal Jwt admin, @PathVariable String userCode) {
        if (admin == null) return ResponseEntity.status(401).build();
        return enrollments.reject(userCode) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
