package com.handwash.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable v1 wire representation of a project-configured friction protocol. */
public record ProtocolResponse(
    String nombre,
    @JsonProperty("duracion_total_ms") long duracionTotalMs,
    @JsonProperty("tiempos_por_paso") Map<String, Long> tiemposPorPaso,
    @JsonProperty("origen_tiempos_por_paso") String origenTiemposPorPaso,
    @JsonProperty("metodo_objetivo") String metodoObjetivo,
    @JsonProperty("alcance_evaluacion") String alcanceEvaluacion,
    @JsonProperty("procedimiento_completo_validado") boolean procedimientoCompletoValidado,
    @JsonProperty("acciones_no_detectadas") List<String> accionesNoDetectadas
) {
    public ProtocolResponse {
        tiemposPorPaso = Collections.unmodifiableMap(new LinkedHashMap<>(tiemposPorPaso));
        accionesNoDetectadas = List.copyOf(accionesNoDetectadas);
    }
}
