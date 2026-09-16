package com.handwash.strategy;

import com.handwash.model.WashStep;

import java.util.List;

public interface ValidationRuleStrategy {
    default String getVersionPolitica() { return "LEGACY_V1"; }
    default List<WashStep> getPasosAcreditables() {
        return java.util.Arrays.stream(WashStep.values()).filter(p -> p != WashStep.FONDO).toList();
    }
    default long getDuracionMinimaFriccionMs() {
        return getPasosAcreditables().stream().mapToLong(this::getTiempoRequeridoPaso).sum();
    }
    boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs);
    long getTiempoRequeridoPaso(WashStep paso);
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

    /** Versioned ADMIN profile that produced this rule, or null for fixed legacy rules. */
    default Integer getVersionPerfilProtocolo() { return null; }

    /** Duration above which a finished wash is only annotated as slow; never a failure. */
    default long getUmbralLavadoLentoMs() { return 0L; }

    default List<String> accionesNoDetectadas() {
        return List.of("MOJAR_MANOS", "APLICAR_JABON", "ENJUAGAR", "SECAR", "CERRAR_GRIFO");
    }
}
