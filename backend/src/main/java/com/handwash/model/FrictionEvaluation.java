package com.handwash.model;

import java.util.List;

/** Immutable public view; heuristic recognition never becomes clinical approval. */
public record FrictionEvaluation(String versionPolitica, long revisionEstado, int numeroIntento,
    String estadoIntento, String pasoEsperado, String motivoRecuperacion,
    List<Step> pasos, long duracionMinimaFriccionMs, long objetivoProcedimientoMs,
    boolean secuenciaValidada, boolean persistenciaPendiente, Result ultimoResultado, KioskGuide guia,
    int puntajePct, String veredicto) {
    public FrictionEvaluation { pasos = List.copyOf(pasos); }

    /** Pre-score shape. */
    public FrictionEvaluation(String versionPolitica, long revisionEstado, int numeroIntento,
        String estadoIntento, String pasoEsperado, String motivoRecuperacion,
        List<Step> pasos, long duracionMinimaFriccionMs, long objetivoProcedimientoMs,
        boolean secuenciaValidada, boolean persistenciaPendiente, Result ultimoResultado, KioskGuide guia) {
        this(versionPolitica, revisionEstado, numeroIntento, estadoIntento, pasoEsperado, motivoRecuperacion, pasos,
            duracionMinimaFriccionMs, objetivoProcedimientoMs, secuenciaValidada, persistenciaPendiente,
            ultimoResultado, guia, 0, null);
    }
    public enum StepStatus { PENDIENTE, EN_CURSO, VALIDADO, REQUIERE_REPETICION }
    /** @param calidadPct how well the step was done: time done against the time asked, times how sure the detector was. */
    public record Step(String paso, StepStatus estado, long tiempoAcreditadoMs,
                       long minimoRequeridoMs, String motivo, int calidadPct) {
        public Step(String paso, StepStatus estado, long tiempoAcreditadoMs, long minimoRequeridoMs, String motivo) {
            this(paso, estado, tiempoAcreditadoMs, minimoRequeridoMs, motivo, 0);
        }
    }
    /** @param veredicto CORRECTO when the average quality reaches the pass mark, RECHAZADO when it does not. */
    public record Result(int numeroIntento, String resultado, String motivo, List<Step> pasos,
                         int puntajePct, String veredicto) {
        public Result { pasos = List.copyOf(pasos); }
        public Result(int numeroIntento, String resultado, String motivo, List<Step> pasos) {
            this(numeroIntento, resultado, motivo, pasos, 0, null);
        }
    }
}
