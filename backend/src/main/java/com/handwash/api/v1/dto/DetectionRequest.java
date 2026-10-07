package com.handwash.api.v1.dto;

import java.util.Map;

/** Transport-only DTO; server-owned monotonic timestamps are intentionally absent. */
public record DetectionRequest(String sessionId, String claseDetectada, Float confianza,
                               String timestamp, String producerEpoch, String eventType,
                               Long frameSequence, Long controlSequence, Long frameWatermark,
                               Long captureAgeMs, MovementEvidenceRequest evidenciaMovimiento,
                               Map<String, SoapEvidenceRequest> evidenciaJabon,
                               Long evidenciaJabonSecuencia, Integer presenceHandsVisible,
                               Map<String, Double> probabilidadesClasificador) {
    /** Pre-F2 shape: no classifier distribution. */
    public DetectionRequest(String sessionId, String claseDetectada, Float confianza,
                            String timestamp, String producerEpoch, String eventType,
                            Long frameSequence, Long controlSequence, Long frameWatermark,
                            Long captureAgeMs, MovementEvidenceRequest evidenciaMovimiento,
                            Map<String, SoapEvidenceRequest> evidenciaJabon,
                            Long evidenciaJabonSecuencia, Integer presenceHandsVisible) {
        this(sessionId, claseDetectada, confianza, timestamp, producerEpoch, eventType,
            frameSequence, controlSequence, frameWatermark, captureAgeMs, evidenciaMovimiento,
            evidenciaJabon, evidenciaJabonSecuencia, presenceHandsVisible, null);
    }
}
