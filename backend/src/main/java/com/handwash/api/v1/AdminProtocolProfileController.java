package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.ProfileChangeResponse;
import com.handwash.api.v1.dto.ProtocolProfileOverviewResponse;
import com.handwash.api.v1.dto.ProtocolProfileRequest;
import com.handwash.api.v1.dto.ProtocolProfileResponse;
import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;
import com.handwash.service.ProtocolProfileService;
import com.handwash.service.SessionAuditService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

/** ADMIN-only, audited editing of the soap-and-water profile; edits append versions. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/protocol-profile")
public final class AdminProtocolProfileController {
    private static final int HISTORY_LIMIT = 20;
    private final ProtocolProfileService profiles;
    private final SessionAuditService audit;

    public AdminProtocolProfileController(ProtocolProfileService profiles, SessionAuditService audit) {
        this.profiles = profiles;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> overview(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_PROTOCOL_PROFILE_READ", null);
            return ResponseEntity.ok(new ProtocolProfileOverviewResponse(
                ProtocolProfileResponse.of(profiles.perfilVigente()),
                profiles.historial(HISTORY_LIMIT).stream().map(ProtocolProfileResponse::of).toList()));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("PERFIL_NO_DISPONIBLE"));
        }
    }

    @PostMapping
    public ResponseEntity<?> createVersion(@AuthenticationPrincipal Jwt admin,
                                           @RequestBody(required = false) ProtocolProfileRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        Map<WashStep, Long> tiempos = parseStepTimes(request);
        if (tiempos == null || request.umbralLentoMs() == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("PERFIL_INVALIDO"));
        }
        try {
            var creado = profiles.crearVersion(tiempos, request.umbralLentoMs(), admin.getSubject(), request.motivo());
            return ResponseEntity.ok(ProfileChangeResponse.of(creado, false));
        } catch (DuplicateKeyException concurrentEdit) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PERFIL_EN_CONFLICTO"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                "MOTIVO_INVALIDO".equals(invalid.getMessage()) ? "MOTIVO_INVALIDO" : "PERFIL_INVALIDO"));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("PERFIL_NO_DISPONIBLE"));
        }
    }

    private static Map<WashStep, Long> parseStepTimes(ProtocolProfileRequest request) {
        if (request == null || request.tiemposPasoMs() == null
            || request.tiemposPasoMs().size() != ProtocolProfile.PASOS.size()) return null;
        EnumMap<WashStep, Long> tiempos = new EnumMap<>(WashStep.class);
        for (WashStep paso : ProtocolProfile.PASOS) {
            Long value = request.tiemposPasoMs().get(paso.name());
            if (value == null) return null;
            tiempos.put(paso, value);
        }
        return tiempos;
    }
}
