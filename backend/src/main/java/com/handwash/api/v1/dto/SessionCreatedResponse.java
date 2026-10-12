package com.handwash.api.v1.dto;

import java.util.List;

public record SessionCreatedResponse(String sessionId, String protocolo, String pairingCode,
                                     String accessToken, String accessTokenExpiresAt,
                                     String mensaje, String metodoObjetivo,
                                     String alcanceEvaluacion,
                                     boolean procedimientoCompletoValidado,
                                     List<String> accionesNoDetectadas) {}
