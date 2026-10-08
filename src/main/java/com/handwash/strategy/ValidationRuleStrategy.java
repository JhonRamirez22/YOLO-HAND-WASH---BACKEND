package com.handwash.strategy;

import com.handwash.model.HandwashingStep;

import java.util.List;

public interface ValidationRuleStrategy {
    boolean validarTiempoPaso(HandwashingStep paso, long tiempoAcumuladoMs);
    long getTiempoRequeridoPaso(HandwashingStep paso);
    long getDuracionTotalMs();

    /** Minimum continuous visual evidence for one OMS action phase. */
    default long getDuracionMinimaFaseOmsMs() { return 600L; }

    /**
     * Metadata explícita del protocolo. Mantenerla en la Strategy evita que
     * los controladores inventen semántica clínica distinta a la regla activa.
     */
    default String getMetodoObjetivo() { return "JABON_Y_AGUA"; }

    default String getAlcanceEvaluacion() {
        return "SECUENCIA_DE_MOVIMIENTOS_DE_FRICCION";
    }

    default boolean procedimientoCompletoValidado() { return false; }

    default List<String> accionesNoDetectadas() {
        return List.of("MOJAR_MANOS", "APLICAR_JABON", "ENJUAGAR", "SECAR", "CERRAR_GRIFO");
    }
}
