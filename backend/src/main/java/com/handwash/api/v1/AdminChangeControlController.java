package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.ProfileChangeResponse;
import com.handwash.model.ProfileChange;
import com.handwash.repository.ChangeControlRepository;
import com.handwash.service.ChangeControlService;
import com.handwash.service.ProtocolProfileService;
import com.handwash.service.SessionAuditService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN-only (CloudSecurityConfig): history with reasons, rollback, approvals, model evidence and reports. */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/change-control")
public final class AdminChangeControlController {
    public record Overview(ChangeControlRepository.Settings ajustes, ProfileChangeResponse vigente,
                           List<ProfileChangeResponse> cambios, List<ChangeControlService.ModelStatus> modelos,
                           List<ChangeControlRepository.Report> informes) {}
    public record ReasonRequest(String motivo) {}
    public record SettingsRequest(Boolean segundoAprobadorRequerido) {}
    public record ReportRequest(String modeloSha256, Integer versionPerfil, String titulo, String resumen, String documentoSha256) {}

    private static final int LIMIT = 30;
    private final ProtocolProfileService profiles;
    private final ChangeControlService control;
    private final SessionAuditService audit;

    public AdminChangeControlController(ProtocolProfileService profiles, ChangeControlService control, SessionAuditService audit) {
        this.profiles = profiles;
        this.control = control;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> overview(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_CHANGE_CONTROL_READ", null);
            var cambios = profiles.cambios(LIMIT).stream()
                .map(cambio -> ProfileChangeResponse.of(cambio, control.profileHasReport(cambio.perfil().version()))).toList();
            var vigente = cambios.stream().filter(c -> ProfileChange.ACTIVA.equals(c.estado())).findFirst().orElse(null);
            return ResponseEntity.ok(new Overview(control.settings(), vigente, cambios, control.models(), control.reports(LIMIT)));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return unavailable();
        }
    }

    @PostMapping("/profile/{version}/revert")
    public ResponseEntity<?> revert(@AuthenticationPrincipal Jwt admin, @PathVariable int version, @RequestBody(required = false) ReasonRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        return changeResult(() -> profiles.revertir(version, admin.getSubject(), request == null ? null : request.motivo()));
    }

    @PostMapping("/profile/{version}/approve")
    public ResponseEntity<?> approve(@AuthenticationPrincipal Jwt admin, @PathVariable int version) {
        if (admin == null) return ResponseEntity.status(401).build();
        return changeResult(() -> profiles.aprobar(version, admin.getSubject()));
    }

    @PostMapping("/profile/{version}/reject")
    public ResponseEntity<?> reject(@AuthenticationPrincipal Jwt admin, @PathVariable int version) {
        if (admin == null) return ResponseEntity.status(401).build();
        return changeResult(() -> profiles.rechazar(version, admin.getSubject()));
    }

    @PutMapping("/settings")
    public ResponseEntity<?> settings(@AuthenticationPrincipal Jwt admin, @RequestBody(required = false) SettingsRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null || request.segundoAprobadorRequerido() == null) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        }
        try {
            return ResponseEntity.ok(control.setSecondApproverRequired(request.segundoAprobadorRequerido(), admin.getSubject()));
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    @PostMapping("/validation-reports")
    public ResponseEntity<?> registerReport(@AuthenticationPrincipal Jwt admin, @RequestBody(required = false) ReportRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null) return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        try {
            return ResponseEntity.ok(control.registerReport(request.modeloSha256(), request.versionPerfil(), request.titulo(),
                request.resumen(), request.documentoSha256(), admin.getSubject()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(invalid.getMessage()));
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    private ResponseEntity<?> changeResult(java.util.function.Supplier<ProfileChange> action) {
        try {
            ProfileChange cambio = action.get();
            return ResponseEntity.ok(ProfileChangeResponse.of(cambio, control.profileHasReport(cambio.perfil().version())));
        } catch (ProtocolProfileService.SameApproverException sameAdmin) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("SE_REQUIERE_OTRO_ADMIN"));
        } catch (DuplicateKeyException concurrentEdit) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("PERFIL_EN_CONFLICTO"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(invalid.getMessage()));
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("CAMBIOS_NO_DISPONIBLES"));
    }
}
