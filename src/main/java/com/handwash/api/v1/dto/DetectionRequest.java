package com.handwash.api.v1.dto;

import java.util.Map;

/** Transport-only DTO; server-owned monotonic timestamps are intentionally absent. */
public record DetectionRequest(String sessionId, String claseDetectada, Float confianza,
                               String timestamp, String producerEpoch, String eventType,
                               Long frameSequence, Long controlSequence, Long frameWatermark,
                               Long captureAgeMs, MovementEvidenceRequest evidenciaMovimiento,
                               Map<String, SoapEvidenceRequest> evidenciaJabon,
                               Long evidenciaJabonSecuencia, Integer presenceHandsVisible) {}
