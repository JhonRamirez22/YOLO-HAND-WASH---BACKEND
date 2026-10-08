package com.handwash.controller;

import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingSession;
import com.handwash.model.HandwashingSessionState;
import com.handwash.service.SessionManager;
import com.handwash.service.HandwashMetrics;
import com.handwash.service.IncompatibleDetectionModeException;
import com.handwash.observer.DetectionPipelineException;
import com.handwash.api.v1.dto.DetectionRequest;
import com.handwash.api.v1.dto.DetectionAckResponse;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.mapper.DetectionRequestMapper;
import com.handwash.api.v1.mapper.SessionResponseMapper;
import com.handwash.security.SessionTokenResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping({"/api/v1", "/api"})
public class DetectionController {
    private static final DetectionAckResponse ACK_ACCEPTED =
        new DetectionAckResponse(true, false, null);
    private static final DetectionAckResponse ACK_FILTERED =
        new DetectionAckResponse(false, true, null);

    private final SessionManager sessionManager;
    private final HandwashMetrics metrics;
    private final DetectionRequestMapper requestMapper;
    private final SessionResponseMapper responseMapper;
    private final SessionTokenResolver tokenResolver;

    public DetectionController(SessionManager sessionManager, HandwashMetrics metrics,
                               DetectionRequestMapper requestMapper, SessionResponseMapper responseMapper,
                               SessionTokenResolver tokenResolver) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.tokenResolver = tokenResolver;
    }

    @PostMapping("/deteccion")
    public ResponseEntity<?> recibir(
        @RequestBody DetectionRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken,
        @RequestParam(value = "includeState", defaultValue = "false") boolean includeState) {
        long requestIngressAtNanos = System.nanoTime();
        String accessToken = tokenResolver.resolve(authorization, legacyToken);
        DetectionEvent evento = requestMapper.toDomain(request);
        if (evento != null) evento.setServerIngressAtMonotonicNanos(requestIngressAtNanos);
        SessionManager.DetectionResult result;
        try {
            result = sessionManager.procesarDeteccionHttp(evento, accessToken);
        } catch (IncompatibleDetectionModeException conflict) {
            return ResponseEntity.status(409).body(ApiErrorResponse.withMessage(
                "MODO_DETECCION_INCOMPATIBLE", conflict.getMessage()));
        } catch (DetectionPipelineException failure) {
            HandwashingSession failedSession = evento == null
                ? null : sessionManager.getSesion(evento.getSessionId());
            return ResponseEntity.internalServerError().body(ApiErrorResponse.withState(
                "ERROR_PROCESAMIENTO",
                failedSession == null ? null
                    : responseMapper.toEvaluation(failedSession.getEstadoActualResponse())));
        }
        if (result.outcome() == SessionManager.DetectionOutcome.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (result.outcome() == SessionManager.DetectionOutcome.UNAUTHORIZED) {
            return ResponseEntity.status(401).body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (result.outcome() == SessionManager.DetectionOutcome.TERMINAL) {
            return ResponseEntity.status(409).body(ApiErrorResponse.of("Sesión terminada"));
        }
        if (result.outcome() == SessionManager.DetectionOutcome.INVALID) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(result.detail()));
        }

        boolean accepted = result.outcome() == SessionManager.DetectionOutcome.ACCEPTED;
        metrics.recordTransportDisposition(accepted);
        ResponseEntity.BodyBuilder ackResponse = ResponseEntity.ok();
        if (result.outcome() == SessionManager.DetectionOutcome.TRANSPORT_REJECTED
            && result.rejectionReason() != null) {
            // Keep the legacy JSON ACK byte-for-byte compatible while allowing
            // the v2 sender to stop a process whose epoch has been superseded.
            ackResponse.header("X-Producer-Rejection-Reason", result.rejectionReason().name());
        }
        // SessionManager serializes validation and observer delivery under the
        // session lock. The hot camera ACK needs no second lock or snapshot;
        // the WebSocket remains the live state channel.
        if (!includeState) {
            return ackResponse.body(accepted ? ACK_ACCEPTED : ACK_FILTERED);
        }
        HandwashingSession sesion = result.session();
        synchronized (sesion) {
            if (sessionManager.getSesion(evento.getSessionId()) != sesion) {
                return ResponseEntity.notFound().build();
            }
            if (!accepted
                && (sesion.getEstadoSesion() == HandwashingSessionState.COMPLETADA
                    || sesion.getEstadoSesion() == HandwashingSessionState.EXPIRADA)) {
                return ResponseEntity.status(409).body(ApiErrorResponse.of("Sesión terminada"));
            }
            // The diagnostic snapshot uses the v1 projection too; the default
            // camera path above still reuses the two allocation-free ACKs.
            return ackResponse.body(new DetectionAckResponse(
                accepted, !accepted, responseMapper.toEvaluation(sesion.getEstadoActualResponse())));
        }
    }
}
