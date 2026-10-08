package com.handwash.controller;

import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingSessionState;
import com.handwash.model.HandwashingSession;
import com.handwash.service.InferenceService;
import com.handwash.service.SessionManager;
import com.handwash.service.IncompatibleDetectionModeException;
import com.handwash.service.ImagePayloadValidator;
import com.handwash.service.ImagePayloadTooLargeException;
import com.handwash.security.SessionTokenResolver;
import com.handwash.api.v1.mapper.SessionResponseMapper;
import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.api.v1.dto.EvaluationResponse;
import com.handwash.api.v1.dto.InferenceResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1", "/api"})
@ConditionalOnProperty(prefix = "handwash.inference", name = "api-enabled", havingValue = "true")
public class InferenceController {

    private final InferenceService inferenceService;
    private final SessionManager sessionManager;
    private final SessionTokenResolver tokenResolver;
    private final SessionResponseMapper responseMapper;
    private final ImagePayloadValidator imagePayloadValidator;

    public InferenceController(
        InferenceService inferenceService,
        SessionManager sessionManager,
        SessionTokenResolver tokenResolver,
        SessionResponseMapper responseMapper,
        ImagePayloadValidator imagePayloadValidator
    ) {
        this.inferenceService = inferenceService;
        this.sessionManager = sessionManager;
        this.tokenResolver = tokenResolver;
        this.responseMapper = responseMapper;
        this.imagePayloadValidator = imagePayloadValidator;
    }

    @PostMapping("/infer")
    public ResponseEntity<?> infer(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "session_id", required = false) String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-Session-Token", required = false) String legacyToken) {
        String accessToken = tokenResolver.resolve(authorization, legacyToken);
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("SESSION_ID_REQUIRED"));
        }
        HandwashingSession sesion = sessionManager.getSesion(sessionId);
        if (sesion == null) return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiErrorResponse.of("SESION_NO_ENCONTRADA"));
        if (!sessionManager.tieneAcceso(sessionId, accessToken, false)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
        }
        if (sesion.getEstadoSesion() == HandwashingSessionState.COMPLETADA
            || sesion.getEstadoSesion() == HandwashingSessionState.EXPIRADA) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("SESION_TERMINADA"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("El archivo de imagen está vacío"));
        }
        byte[] imageBytes;
        try {
            imagePayloadValidator.validateFileSize(file.getSize());
            imageBytes = file.getBytes();
            imagePayloadValidator.validate(imageBytes);
        } catch (ImagePayloadTooLargeException tooLarge) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiErrorResponse.of("IMAGEN_EXCEDE_LIMITE"));
        } catch (IOException malformedImage) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("IMAGEN_INVALIDA"));
        }

        Map<String, Object> result = inferenceService.infer(imageBytes);
        if (result.containsKey("error")) {
            if ("INFERENCE_BUSY".equals(result.get("errorCode"))) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiErrorResponse.of("INFERENCIA_OCUPADA"));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrorResponse.of("ONNX_INFERENCE_UNAVAILABLE"));
        }
        EvaluationResponse sessionState = null;

        if (sessionId != null && !sessionId.isBlank()) {
            result.put("sessionId", sessionId);
            Object claseBackend = result.get("claseBackend");
            Object confianza = result.get("pasoConfianza");
            if (claseBackend instanceof String clase && confianza instanceof Number score) {
                try {
                    SessionManager.DetectionResult decision = sessionManager.procesarDeteccionHttp(
                        new DetectionEvent(sessionId, clase, score.floatValue(), Instant.now().toString()),
                        accessToken);
                    ResponseEntity<?> rejected = rejectionResponse(decision);
                    if (rejected != null) return rejected;

                    // Be explicit that this diagnostic inference was accepted by
                    // Java's ingestion path or filtered; neither result means a
                    // clinical step was credited by the state machine.
                    result.put("resultadoIngresoBackend",
                        decision.outcome() == SessionManager.DetectionOutcome.ACCEPTED
                            ? "PROCESADA" : "FILTRADA");
                    HandwashingSession sesionActual = decision.session();
                    if (sesionActual != null) {
                        synchronized (sesionActual) {
                            if (sessionManager.getSesion(sessionId) != sesionActual) {
                                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                    .body(ApiErrorResponse.of("SESION_NO_ENCONTRADA"));
                            }
                            sessionState = responseMapper.toEvaluation(
                                sesionActual.getEstadoActualResponse());
                        }
                    }
                } catch (IncompatibleDetectionModeException unavailable) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.withMessage(
                        "MODO_DETECCION_NO_DISPONIBLE", unavailable.getMessage()));
                }
            }
        }

        return ResponseEntity.ok(InferenceResponse.from(result, "ONNX_LOCAL", sessionId, sessionState));
    }

    /** Convenience overload for direct, non-HTTP tests using the legacy header contract. */
    public ResponseEntity<?> infer(
        MultipartFile file, String sessionId, String legacyToken) {
        return infer(file, sessionId, null, legacyToken);
    }

    private ResponseEntity<?> rejectionResponse(SessionManager.DetectionResult decision) {
        return switch (decision.outcome()) {
            case ACCEPTED, FILTERED -> null;
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("SESION_NO_ENCONTRADA"));
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));
            case TERMINAL -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("SESION_TERMINADA"));
            case INVALID -> ResponseEntity.badRequest().body(ApiErrorResponse.withMessage(
                "DETECCION_INVALIDA", decision.detail()));
            case TRANSPORT_REJECTED -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.withMessage("EVENTO_PRODUCTOR_RECHAZADO",
                    decision.detail() == null ? "El backend rechazó el sobre del productor."
                        : decision.detail()));
        };
    }

}
