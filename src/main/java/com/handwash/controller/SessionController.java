package com.handwash.controller;

import com.handwash.model.SesionLavado;
import com.handwash.model.EstadoSesion;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.SessionManager;
import com.handwash.service.SessionCapacityException;
import com.handwash.agent.Notificador;
import com.handwash.strategy.ReglaValidacionStrategy;
import com.handwash.service.RequestRateLimiter;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.api.v1.dto.ActiveSessionResponse;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.DevicePairResponse;
import com.handwash.api.v1.dto.MessageResponse;
import com.handwash.api.v1.dto.OmsProtocolResponse;
import com.handwash.api.v1.dto.ProducerEpochResponse;
import com.handwash.api.v1.dto.ProtocolResponse;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import com.handwash.api.v1.dto.SessionDetailsResponse;
import com.handwash.api.v1.dto.SessionPairRequest;
import com.handwash.api.v1.mapper.ProtocolCatalogMapper;
import com.handwash.api.v1.mapper.SessionResponseMapper;
import com.handwash.security.SessionAuthenticationService;
import com.handwash.security.SessionTokenResolver;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping({"/api/v1", "/api"})
public class SessionController {

    private final SessionManager sessionManager;
    private final ProtocolCatalogMapper protocolCatalogMapper;
    private final SessionResponseMapper sessionResponseMapper;
    private final Notificador notificador;
    private final RequestRateLimiter rateLimiter;
    private final FailedAttemptStore failedAttemptRepository;
    private final SessionAuthenticationService authenticationService;
    private final SessionTokenResolver tokenResolver;
    @Value("${handwash.producer.require-v2:false}")
    private boolean producerV2Required;

    public SessionController(SessionManager sessionManager,
                             ProtocolCatalogMapper protocolCatalogMapper,
                             SessionResponseMapper sessionResponseMapper,
                             Notificador notificador,
                             RequestRateLimiter rateLimiter,
                             FailedAttemptStore failedAttemptRepository,
                             SessionAuthenticationService authenticationService,
                             SessionTokenResolver tokenResolver) {
        this.sessionManager = sessionManager;
        this.protocolCatalogMapper = protocolCatalogMapper;
        this.sessionResponseMapper = sessionResponseMapper;
        this.notificador = notificador;
        this.rateLimiter = rateLimiter;
        this.failedAttemptRepository = failedAttemptRepository;
        this.authenticationService = authenticationService;
        this.tokenResolver = tokenResolver;
    }

    @PostMapping("/session")
    public ResponseEntity<?> crearSesion(
        @RequestBody SessionCreateRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.allowSessionCreation(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_CREACION_SESIONES"));
        }
        try {
            String protocoloStr = request == null ? null : request.protocolo();
            TipoProtocolo protocolo = TipoProtocolo.fromRequestValue(protocoloStr);
            int producerProtocolVersion = producerProtocolVersion(
                request == null ? null : request.producerProtocolVersion());
            if (producerV2Required && producerProtocolVersion != 2) {
                return ResponseEntity.status(409).body(ApiErrorResponse.producerVersionRequired());
            }
            String sessionId = sessionManager.crearSesion(protocolo, producerProtocolVersion == 2);
            ReglaValidacionStrategy strategy = sessionManager.getSesion(sessionId).getEstrategia();
            return ResponseEntity.ok(new SessionCreatedResponse(
                sessionId,
                protocolo.value(),
                sessionManager.getCodigoEmparejamiento(sessionId),
                sessionManager.getOwnerToken(sessionId),
                Instant.ofEpochMilli(sessionManager.getOwnerTokenExpirationEpochMs(sessionId)).toString(),
                "Sesion creada. Conecta via WebSocket para recibir actualizaciones.",
                strategy.getMetodoObjetivo(),
                strategy.getAlcanceEvaluacion(),
                strategy.procedimientoCompletoValidado(),
                strategy.accionesNoDetectadas()));
        } catch (SessionCapacityException e) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_SESIONES"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(e.getMessage()));
        }
    }

    @GetMapping("/session/active")
    public ResponseEntity<?> obtenerSesionActiva() {
        List<SesionLavado> activas = sessionManager.getSesionesActivas();
        if (activas.isEmpty()) return ResponseEntity.noContent().build();
        if (activas.size() > 1) return ResponseEntity.status(409).body(ApiErrorResponse.withMessage(
            "SESIONES_ACTIVAS_AMBIGUAS", "Hay varias sesiones activas; usa el código de vinculación"));
        SesionLavado sesion = activas.get(0);
        synchronized (sesion) {
            if (sessionManager.getSesion(sesion.getSessionId()) != sesion
                || sesion.getEstadoSesion() == EstadoSesion.COMPLETADA
                || sesion.getEstadoSesion() == EstadoSesion.EXPIRADA) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(new ActiveSessionResponse(
                sesion.getSessionId(),
                sesion.getProtocolo().value(),
                sesion.getEstadoSesion().name(),
                sessionManager.isAccessRequired()));
        }
    }

    @PostMapping("/session/pair")
    public ResponseEntity<?> vincularSesion(
        @RequestBody SessionPairRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.allowPairing(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("LIMITE_INTENTOS_VINCULACION"));
        }
        String codigo = request == null ? null : request.code();
        if (codigo == null || codigo.isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("code es obligatorio"));
        }
        final int producerProtocolVersion;
        try {
            producerProtocolVersion = producerProtocolVersion(
                request == null ? null : request.producerProtocolVersion());
        } catch (IllegalArgumentException unsupportedVersion) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(unsupportedVersion.getMessage()));
        }
        if (producerV2Required && producerProtocolVersion != 2) {
            return ResponseEntity.status(409).body(ApiErrorResponse.producerVersionRequired());
        }
        SessionAuthenticationService.DeviceLogin result =
            authenticationService.loginWithPairingCode(codigo, producerProtocolVersion);
        if (!result.successful()) return pairingFailure(result.failure());
        return ResponseEntity.ok(new DevicePairResponse(
            result.session().getSessionId(), result.session().getProtocolo().value(),
            result.token().value(), Instant.ofEpochMilli(result.token().expiresAtEpochMs()).toString(),
            result.protocolVersion()));
    }

    /** Registers a fresh producer process using the session's existing owner/device capability. */
    @PostMapping("/session/{sessionId}/producer-epoch")
    public ResponseEntity<?> registrarProducerEpoch(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        String accessToken = tokenResolver.resolve(authorization, legacyToken);
        SessionManager.ProducerEpochResult result =
            sessionManager.registrarProducerEpoch(sessionId, accessToken);
        if (result.outcome() == SessionManager.ProducerEpochOutcome.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (result.outcome() == SessionManager.ProducerEpochOutcome.UNAUTHORIZED) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (result.outcome() == SessionManager.ProducerEpochOutcome.TERMINAL) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_TERMINADA"));
        }
        SessionManager.ProducerEpochRegistration registration = result.registration();
        return ResponseEntity.ok(new ProducerEpochResponse(sessionId, registration.producerEpoch(),
            registration.protocolVersion(), registration.rotated()));
    }

    private int producerProtocolVersion(String requested) {
        if (requested == null || requested.isBlank() || "1".equals(requested)) return 1;
        if ("2".equals(requested)) return 2;
        throw new IllegalArgumentException("producerProtocolVersion debe ser 1 o 2");
    }

    private ResponseEntity<?> pairingFailure(SessionAuthenticationService.Failure failure) {
        if (failure == SessionAuthenticationService.Failure.INVALID_CODE) {
            return ResponseEntity.status(404).body(ApiErrorResponse.of("CODIGO_NO_VALIDO"));
        }
        if (failure == SessionAuthenticationService.Failure.PRODUCER_VERSION_REQUIRED) {
            return ResponseEntity.status(409).body(ApiErrorResponse.producerVersionRequired());
        }
        return ResponseEntity.status(409).body(ApiErrorResponse.of("SESION_TERMINADA"));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> obtenerSesion(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        String accessToken = tokenResolver.resolve(authorization, legacyToken);
        SesionLavado sesion = sessionManager.getSesion(sessionId);
        if (sesion == null) {
            return ResponseEntity.notFound().build();
        }
        synchronized (sesion) {
            if (sessionManager.getSesion(sessionId) != sesion) {
                return ResponseEntity.notFound().build();
            }
            if (!sessionManager.tieneAccesoLectura(sessionId, accessToken)) {
                return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            }
            String codigo = sessionManager.getCodigoEmparejamiento(sessionId);
            // Permite recuperar también la intención tras una reconexión REST;
            // conserva el campo "estado" que utiliza el monitor de cámara.
            String pairingCode = codigo != null && sessionManager.tieneAcceso(sessionId, accessToken, true)
                ? codigo : null;
            // Keep the envelope and its nested evaluation from one domain revision.
            return ResponseEntity.ok(new SessionDetailsResponse(
                sesion.getSessionId(), sesion.getProtocolo().value(),
                sesion.getEstadoSesion().name(), sesion.getDuracionMs(),
                sessionResponseMapper.toEvaluation(sesion.getEstadoActualResponse()), pairingCode));
        }
    }

    @GetMapping("/session/{sessionId}/attempts")
    public ResponseEntity<?> obtenerIntentosFallidos(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        String accessToken = tokenResolver.resolve(authorization, legacyToken);
        if (sessionManager.getSesion(sessionId) == null) return ResponseEntity.notFound().build();
        if (!sessionManager.tieneAccesoLectura(sessionId, accessToken)) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (!sessionManager.persistirIntentosFallidosPendientes(sessionId)) {
            return ResponseEntity.status(503).body(ApiErrorResponse.withMessage(
                "CACHE_INTENTOS_NO_DISPONIBLE",
                "No fue posible confirmar la persistencia del historial; intenta de nuevo."));
        }
        return ResponseEntity.ok(sessionResponseMapper.toFailedAttempts(
            sessionId, failedAttemptRepository.findBySession(sessionId)));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<?> eliminarSesion(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        return eliminarSesionAutorizada(sessionId, tokenResolver.resolve(authorization, legacyToken));
    }

    private ResponseEntity<?> eliminarSesionAutorizada(
        String sessionId, String accessToken) {
        if (sessionManager.getSesion(sessionId) == null) return ResponseEntity.notFound().build();
        if (!sessionManager.tieneAcceso(sessionId, accessToken, true)) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (!sessionManager.eliminarSesion(sessionId)) {
            return ResponseEntity.status(503).body(ApiErrorResponse.withMessage(
                "ELIMINACION_NO_CONFIRMADA",
                "No se pudo confirmar el borrado de la caché; la sesión permanece disponible para reintentar."));
        }
        notificador.eliminarSesion(sessionId);
        return ResponseEntity.ok(new MessageResponse("Sesion eliminada"));
    }

    @PatchMapping("/session/{sessionId}")
    public ResponseEntity<?> detenerSesionCompatible(
        @PathVariable String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        return eliminarSesionAutorizada(sessionId, tokenResolver.resolve(authorization, legacyToken));
    }

    @GetMapping("/protocols")
    public ResponseEntity<Map<String, ProtocolResponse>> obtenerProtocolos() {
        return ResponseEntity.ok(protocolCatalogMapper.frictionProtocols());
    }

    /** Reference catalog, not a claim that the currently installed model observes every action. */
    @GetMapping("/protocols/oms")
    public ResponseEntity<OmsProtocolResponse> obtenerSecuenciaOms() {
        return ResponseEntity.ok(protocolCatalogMapper.soapAndWaterReference());
    }
}
