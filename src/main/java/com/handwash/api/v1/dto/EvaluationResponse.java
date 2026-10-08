package com.handwash.api.v1.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable v1 projection of evaluation state, independent of domain response classes. */
public record EvaluationResponse(
    String sessionId,
    String estadoIntencion,
    String motivoIntencion,
    long tiempoConfirmacionIntencionMs,
    long umbralConfirmacionIntencionMs,
    int manosVisibles,
    String claseCandidata,
    Float confianzaCandidata,
    String estadoActual,
    long tiempoAcumuladoMs,
    ViolationResponse infraccion,
    ProgressResponse progreso,
    Boolean manoDetectada,
    Float confianzaDeteccion,
    Float manoConfianza,
    String estadoSesion,
    String messageType,
    List<ViolationResponse> historialInfracciones,
    int intentosReiniciados,
    ViolationResponse ultimoErrorReinicio,
    String modoEvaluacion,
    Map<String, String> coberturaJabon,
    boolean coberturaJabonCompleta,
    boolean procedimientoCompletoValidado,
    long tiempoTotalActivoMs,
    long duracionMinimaObjetivoMs
) {
    public EvaluationResponse {
        historialInfracciones = historialInfracciones == null
            ? null : List.copyOf(historialInfracciones);
        coberturaJabon = coberturaJabon == null
            ? null : Collections.unmodifiableMap(new LinkedHashMap<>(coberturaJabon));
    }
}
