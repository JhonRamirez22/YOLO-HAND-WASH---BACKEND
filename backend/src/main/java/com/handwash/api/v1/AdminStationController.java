package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.StationAdminRecord;
import com.handwash.api.v1.dto.StationEnrollmentCodeRequest;
import com.handwash.api.v1.dto.StationEnrollmentCodeResponse;
import com.handwash.api.v1.dto.OperatorAccountRequest;
import com.handwash.api.v1.dto.StationProducerBindingRequest;
import com.handwash.api.v1.dto.StationProvisionRequest;
import com.handwash.api.v1.dto.StationProvisionResponse;
import com.handwash.service.OperatorAccountDirectory;
import com.handwash.security.ApiKeyService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.model.StationRecord;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
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
@RequestMapping("/api/v1/admin/stations")
public final class AdminStationController {
    private final StationCredentialService stations;
    private final SessionAuditService audit;
    private final StationOperatorAccessService operators;
    private final ApiKeyService apiKeys;

    public AdminStationController(StationCredentialService stations, SessionAuditService audit,
                                  StationOperatorAccessService operators, ApiKeyService apiKeys) {
        this.stations = stations;
        this.audit = audit;
        this.operators = operators;
        this.apiKeys = apiKeys;
    }

    /** One step: station, producer binding, operator accounts and the Mac's one-time activation token. */
    @PostMapping("/provision")
    public ResponseEntity<?> provision(@AuthenticationPrincipal Jwt admin,
                                       @RequestBody(required = false) StationProvisionRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null) return ResponseEntity.badRequest().body(ApiErrorResponse.of("NOMBRE_DE_PUESTO_INVALIDO"));
        List<String> emails = request.operatorEmails() == null ? List.of() : request.operatorEmails();
        if (emails.size() > 20) return ResponseEntity.badRequest().body(ApiErrorResponse.of("DEMASIADOS_OPERADORES"));
        if (!emails.isEmpty() && !operators.accountsAvailable()) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("POOL_OPERADORES_NO_CONFIGURADO"));
        }
        StationCredentialService.ProvisionedStation created;
        try {
            created = stations.provision(request.stationName(), request.producerClientId(), admin.getSubject());
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage() == null ? "" : invalid.getMessage();
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                message.contains("clientId") ? "CLIENT_ID_INVALIDO" : "NOMBRE_DE_PUESTO_INVALIDO"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("PUESTOS_NO_DISPONIBLES"));
        }
        List<StationProvisionResponse.OperatorAccount> accounts = new java.util.ArrayList<>();
        List<StationProvisionResponse.OperatorFailure> failures = new java.util.ArrayList<>();
        for (String email : emails) {
            try {
                var account = operators.createAccount(created.station().stationId(), email, admin.getSubject());
                accounts.add(new StationProvisionResponse.OperatorAccount(
                    account.subject(), account.email(), account.temporaryPassword()));
            } catch (OperatorAccountDirectory.AlreadyExistsException exists) {
                failures.add(new StationProvisionResponse.OperatorFailure(email, "OPERADOR_YA_EXISTE"));
            } catch (IllegalArgumentException invalid) {
                failures.add(new StationProvisionResponse.OperatorFailure(email, "CORREO_INVALIDO"));
            } catch (RuntimeException unavailable) {
                failures.add(new StationProvisionResponse.OperatorFailure(email, "OPERADOR_NO_CREADO"));
            }
        }
        return ResponseEntity.ok(new StationProvisionResponse(created.station().stationId(),
            created.station().stationName(), created.station().producerClientId(), created.activationToken(),
            created.expiresAtEpochMs(), accounts, failures));
    }

    @PostMapping("/{stationId}/activation")
    public ResponseEntity<?> reissueActivation(@AuthenticationPrincipal Jwt admin,
                                               @PathVariable String stationId) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            var issued = stations.reissueActivation(stationId, admin.getSubject());
            return ResponseEntity.ok(new StationEnrollmentCodeResponse(issued.code(), issued.expiresAtEpochMs()));
        } catch (IllegalArgumentException unavailable) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("ACTIVACION_NO_DISPONIBLE"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("ACTIVACION_NO_DISPONIBLE"));
        }
    }

    @PostMapping("/operator-accounts")
    public ResponseEntity<?> createOperator(@AuthenticationPrincipal Jwt admin,
                                            @RequestBody(required = false) OperatorAccountRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        if (request == null) return ResponseEntity.badRequest().body(ApiErrorResponse.of("SOLICITUD_INVALIDA"));
        if (!operators.accountsAvailable()) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("POOL_OPERADORES_NO_CONFIGURADO"));
        }
        try {
            var account = operators.createAccount(request.stationId(), request.email(), admin.getSubject());
            return ResponseEntity.ok(new StationProvisionResponse.OperatorAccount(
                account.subject(), account.email(), account.temporaryPassword()));
        } catch (OperatorAccountDirectory.AlreadyExistsException exists) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("OPERADOR_YA_EXISTE"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("CORREO_O_PUESTO_INVALIDO"));
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("OPERADOR_NO_CREADO"));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt admin) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            audit.recordAdminAccess(admin.getSubject(), "ADMIN_STATIONS_LISTED", null);
            java.util.Map<String, Long> activation = stations.activationTimes();
            List<StationAdminRecord> records = stations.listStations().stream()
                .map(station -> toAdminRecord(station, activation.get(station.stationId()))).toList();
            return ResponseEntity.ok(records);
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("PUESTOS_NO_DISPONIBLES"));
        }
    }

    @PostMapping("/enrollment-codes")
    public ResponseEntity<?> createEnrollmentCode(@AuthenticationPrincipal Jwt admin,
        @RequestBody(required = false) StationEnrollmentCodeRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            StationCredentialService.IssuedEnrollmentCode issued = stations.issueEnrollmentCode(
                request == null ? null : request.stationName(), admin.getSubject());
            return ResponseEntity.ok(new StationEnrollmentCodeResponse(
                issued.code(), issued.expiresAtEpochMs()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("NOMBRE_DE_PUESTO_INVALIDO"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("CODIGO_DE_INSCRIPCION_NO_DISPONIBLE"));
        }
    }

    @DeleteMapping("/{stationId}")
    public ResponseEntity<?> revoke(@AuthenticationPrincipal Jwt admin,
                                    @PathVariable String stationId) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            if (!stations.revoke(stationId, admin.getSubject())) return ResponseEntity.notFound().build();
            apiKeys.revokeAllForStation(stationId, admin.getSubject());
            return ResponseEntity.noContent().build();
        } catch (DataAccessException | IllegalArgumentException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("REVOCACION_NO_DISPONIBLE"));
        }
    }

    @org.springframework.web.bind.annotation.PutMapping("/{stationId}/producer")
    public ResponseEntity<?> bindProducer(@AuthenticationPrincipal Jwt admin,
        @PathVariable String stationId,
        @RequestBody(required = false) StationProducerBindingRequest request) {
        if (admin == null) return ResponseEntity.status(401).build();
        try {
            stations.bindProducerClient(stationId,
                request == null ? null : request.clientId(), admin.getSubject());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("CLIENT_ID_INVALIDO"));
        } catch (DataAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of("VINCULO_PRODUCTOR_NO_DISPONIBLE"));
        }
    }

    private static StationAdminRecord toAdminRecord(StationRecord station, Long activatedAtEpochMs) {
        return new StationAdminRecord(station.stationId(), station.stationName(),
            station.createdAtEpochMs(), station.createdBy(), station.revokedAtEpochMs(),
            station.lastStartedAtEpochMs(), station.activeSessionId(), station.producerClientId(), activatedAtEpochMs);
    }
}
