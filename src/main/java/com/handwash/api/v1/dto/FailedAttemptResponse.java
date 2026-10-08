package com.handwash.api.v1.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned metadata-only view of one restarted attempt. */
public record FailedAttemptResponse(
    int numero,
    String resultado,
    String motivoReinicio,
    long duracionMs,
    Map<String, Long> tiempoPorPasoMs,
    List<ViolationResponse> infracciones
) {
    public FailedAttemptResponse {
        tiempoPorPasoMs = Collections.unmodifiableMap(new LinkedHashMap<>(tiempoPorPasoMs));
        infracciones = List.copyOf(infracciones);
    }
}
