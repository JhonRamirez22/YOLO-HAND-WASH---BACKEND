package com.handwash.model;

/** Metadatos efímeros; nunca incluye imágenes ni coordenadas identificables. */
public record EvidenciaMovimiento(Long secuencia, Integer manosVisibles,
                                  Double movimientoNormalizado, Boolean medicionValida,
                                  Long antiguedadMs) {
    public static final long MAX_EDAD_RECIENTE_MS = 500L;

    public boolean esReciente() {
        return antiguedadMs != null && antiguedadMs >= 0L
            && antiguedadMs <= MAX_EDAD_RECIENTE_MS;
    }

    public String validar() {
        if (secuencia == null || secuencia < 0) return "secuencia debe ser no negativa";
        if (manosVisibles == null || manosVisibles < 0 || manosVisibles > 2)
            return "manosVisibles debe estar entre 0 y 2";
        if (movimientoNormalizado == null || !Double.isFinite(movimientoNormalizado)
            || movimientoNormalizado < 0 || movimientoNormalizado > 1)
            return "movimientoNormalizado debe estar entre 0 y 1";
        if (medicionValida == null) return "medicionValida es obligatoria";
        if (Boolean.TRUE.equals(medicionValida) && manosVisibles != 2)
            return "la medición válida requiere dos manos";
        if (antiguedadMs == null || antiguedadMs < 0 || antiguedadMs > 60_000)
            return "antiguedadMs debe estar entre 0 y 60000";
        return null;
    }
}
