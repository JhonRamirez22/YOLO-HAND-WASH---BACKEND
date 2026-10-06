package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.OperatorAccessCodeIssuedResponse;
import com.handwash.api.v1.dto.OperatorAccessCodeRecord;
import com.handwash.api.v1.dto.OperatorAccessCodeRequest;
import com.handwash.api.v1.dto.OperatorGrantRecord;
import com.handwash.agent.Notifier;
import com.handwash.model.StationRecord;
import com.handwash.service.StationCredentialService;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationOperatorAccessService;
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

import java.util.List;

@RestController
@Profile("cloud")
@RequestMapping("/api/v1/admin/operators")
public final class AdminOperatorAccessController {
    private final StationOperatorAccessService operators;
    private final SessionAuditService audit;
    private final StationCredentialService stations;
    private final Notifier notifier;

    public AdminOperatorAccessController(StationOperatorAccessService operators,
        SessionAuditService audit, StationCredentialService stations, Notifier notifier) {
        this.operators = operators;
        this.audit = audit;
        this.stations = stations;
        this.notifier = notifier;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_OPERATORS_LISTED", null);
            List<OperatorGrantRecord> records = operators.listGrants().stream()
                .map(grant -> new OperatorGrantRecord(grant.subject(), grant.email(), grant.stationId(),
                    grant.stationName(), grant.grantedBy(), grant.grantedAtEpochMs(), grant.revokedAtEpochMs()))
                .toList();
            return ResponseEntity.ok(records);
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("OPERADORES_NO_DISPONIBLES"));
        }
    }

    @GetMapping("/codes")
    public ResponseEntity<?> codes(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_OPERATOR_CODES_LISTED", null);
            List<OperatorAccessCodeRecord> records = operators.listCodes().stream()
                .map(code -> new OperatorAccessCodeRecord(code.codeId(), code.stationId(), code.email(),
                    code.createdBy(), code.createdAtEpochMs(), code.consumedAtEpochMs(),
                    code.consumedBy(), code.revokedAtEpochMs()))
                .toList();
            return ResponseEntity.ok(records);
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("CODIGOS_OPERADOR_NO_DISPONIBLES"));
        }
    }

    @PostMapping("/codes")
    public ResponseEntity<?> issue(@AuthenticationPrincipal Jwt admin,
        @RequestBody(required = false) OperatorAccessCodeRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            StationOperatorAccessService.IssuedOperatorCode issued = operators.issue(
                request == null ? null : request.stationId(), request == null ? null : request.email(),
                admin.getSubject());
            return ResponseEntity.ok(new OperatorAccessCodeIssuedResponse(issued.codeId(), issued.code(),
                issued.stationId(), issued.email(), issued.createdAtEpochMs()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("ASIGNACION_OPERADOR_INVALIDA"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("CODIGO_OPERADOR_NO_DISPONIBLE"));
        }
    }

    @DeleteMapping("/codes/{codeId}")
    public ResponseEntity<?> revokeCode(@AuthenticationPrincipal Jwt admin,
                                        @PathVariable String codeId) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            if (!operators.revokeCode(codeId, admin.getSubject())) return ResponseEntity.notFound().build();
            return ResponseEntity.noContent().build();
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REVOCACION_CODIGO_NO_DISPONIBLE"));
        }
    }

    @DeleteMapping("/{subject}")
    public ResponseEntity<?> revokeGrant(@AuthenticationPrincipal Jwt admin,
                                         @PathVariable String subject) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            String stationId = operators.revokeGrant(subject, admin.getSubject());
            if (stationId == null) return ResponseEntity.notFound().build();
            StationRecord station = stations.activeStation(stationId);
            if (station != null && station.activeSessionId() != null) {
                notifier.desconectarPropietarios(station.activeSessionId());
            }
            return ResponseEntity.noContent().build();
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REVOCACION_OPERADOR_NO_DISPONIBLE"));
        }
    }
}
