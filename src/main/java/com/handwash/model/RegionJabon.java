package com.handwash.model;

import java.util.Arrays;
import java.util.Locale;

/** Bilateral hand regions for explicit visual soap-foam evidence. */
public enum RegionJabon {
    PALMA_IZQUIERDA,
    PALMA_DERECHA,
    DORSO_IZQUIERDO,
    DORSO_DERECHO,
    INTERDIGITALES_IZQUIERDA,
    INTERDIGITALES_DERECHA,
    DORSO_DE_DEDOS_IZQUIERDO,
    DORSO_DE_DEDOS_DERECHO,
    PULGAR_IZQUIERDO,
    PULGAR_DERECHO,
    PUNTAS_DE_DEDOS_IZQUIERDA,
    PUNTAS_DE_DEDOS_DERECHA;

    public String claseEspumaVisible() { return "ESPUMA_VISIBLE_" + name(); }
    public String claseSinEspumaVisible() { return "SIN_ESPUMA_VISIBLE_" + name(); }

    /** Foam is only credited while the matching bilateral rubbing action is observed. */
    public AccionOms getAccionVerificacion() {
        return switch (this) {
            case PALMA_IZQUIERDA, PALMA_DERECHA -> AccionOms.FROTAR_PALMAS;
            case DORSO_IZQUIERDO, DORSO_DERECHO -> AccionOms.FROTAR_DORSOS;
            case INTERDIGITALES_IZQUIERDA, INTERDIGITALES_DERECHA -> AccionOms.FROTAR_ENTRE_DEDOS;
            case DORSO_DE_DEDOS_IZQUIERDO, DORSO_DE_DEDOS_DERECHO -> AccionOms.FROTAR_DORSO_DE_DEDOS;
            case PULGAR_IZQUIERDO, PULGAR_DERECHO -> AccionOms.FROTAR_PULGARES;
            case PUNTAS_DE_DEDOS_IZQUIERDA, PUNTAS_DE_DEDOS_DERECHA -> AccionOms.FROTAR_PUNTAS_DE_DEDOS;
        };
    }

    public static RegionJabon from(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(region -> region.name().equals(key)
            || region.claseEspumaVisible().equals(key)
            || region.claseSinEspumaVisible().equals(key)).findFirst().orElse(null);
    }

    public static RegionJabon fromModelClass(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(region -> region.claseEspumaVisible().equals(key)
            || region.claseSinEspumaVisible().equals(key)).findFirst().orElse(null);
    }
}
