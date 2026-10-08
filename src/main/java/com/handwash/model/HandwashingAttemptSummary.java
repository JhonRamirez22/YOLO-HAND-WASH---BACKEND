package com.handwash.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable record of a failed/restarted handwashing attempt. */
public record HandwashingAttemptSummary(
    int numero,
    String resultado,
    String motivoReinicio,
    long duracionMs,
    Map<String, Long> tiempoPorPasoMs,
    List<Violation> infracciones
) {
    public HandwashingAttemptSummary {
        tiempoPorPasoMs = Collections.unmodifiableMap(new LinkedHashMap<>(tiempoPorPasoMs));
        infracciones = List.copyOf(infracciones);
    }
}
