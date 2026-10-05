package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.model.StationRecord;
import com.handwash.model.StationShift;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.service.StationShiftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Not final: its transactional handlers need a class proxy (see OperatorAccessProxyTest). */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/operator/shift")
public class OperatorShiftController {
    private final StationOperatorAccessService access;
    private final StationCredentialService stations;
    private final StationShiftService shifts;
    private final RequestRateLimiter rateLimiter;
    private final String userClientId;

    public OperatorShiftController(StationOperatorAccessService access, StationCredentialService stations,
                                   StationShiftService shifts, RequestRateLimiter rateLimiter,
                                   @Value("${handwash.security.cognito-client-id}") String userClientId) {
        this.access = access;
        this.stations = stations;
        this.shifts = shifts;
        this.rateLimiter = rateLimiter;
        this.userClientId = userClientId;
    }

    public record ShiftRequest(Integer hours, String protocolo) {}

    public record ShiftView(boolean active, String protocolo, Long startedAtEpochMs, Long endsAtEpochMs) {
        static ShiftView of(StationShift shift) {
            return shift == null ? new ShiftView(false, null, null, null)
                : new ShiftView(true, shift.protocolCode(), shift.startedAtEpochMs(), shift.endsAtEpochMs());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> status(@AuthenticationPrincipal Jwt operator) {
        try {
            StationRecord station = authorizedStation(operator, false);
            if (station == null) return forbidden();
            return ResponseEntity.ok(ShiftView.of(shifts.openShift(station.stationId()).orElse(null)));
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt operator,
                                   @RequestBody(required = false) ShiftRequest request,
                                   HttpServletRequest httpRequest) {
        try {
            StationRecord station = authorizedStation(operator, true);
            if (station == null) return forbidden();
            if (!rateLimiter.allowSessionCreation(com.handwash.security.ClientAddress.of(httpRequest))) {
                return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_CREACION_SESIONES"));
            }
            if (request == null || request.hours() == null) {
                return ResponseEntity.badRequest().body(ApiErrorResponse.of("TURNO_INVALIDO"));
            }
            var result = shifts.start(station, operator.getSubject(), request.hours(), request.protocolo());
            return switch (result.outcome()) {
                case STARTED -> ResponseEntity.ok(ShiftView.of(result.shift()));
                case ALREADY_OPEN -> ResponseEntity.status(409).body(ApiErrorResponse.of("TURNO_YA_ACTIVO"));
                case INVALID -> ResponseEntity.badRequest().body(ApiErrorResponse.of("TURNO_INVALIDO"));
            };
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<?> stop(@AuthenticationPrincipal Jwt operator) {
        try {
            StationRecord station = authorizedStation(operator, true);
            if (station == null) return forbidden();
            return shifts.stop(station, operator.getSubject())
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    private StationRecord authorizedStation(Jwt operator, boolean forUpdate) {
        if (operator == null || !(access.isHumanClient(operator.getClaimAsString("client_id"), userClientId)
            || access.isOperatorPoolClient(operator.getClaimAsString("client_id")))) return null;
        var grant = forUpdate ? access.activeGrantForUpdate(operator.getSubject()) : access.activeGrant(operator.getSubject());
        return grant == null ? null : stations.activeStation(grant.stationId());
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(ApiErrorResponse.of("OPERADOR_SIN_ASIGNACION"));
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(ApiErrorResponse.of("TURNO_NO_DISPONIBLE"));
    }
}
