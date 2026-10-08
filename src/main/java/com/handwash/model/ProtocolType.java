package com.handwash.model;

import java.util.Locale;

public enum ProtocolType {
    CLINICO_QUIRURGICO("Secuencia de 7 movimientos (objetivo 60 s; evaluación parcial)"),
    DOMESTICO("Secuencia de 7 movimientos (objetivo 40 s; evaluación parcial)");

    private final String nombre;

    ProtocolType(String nombre) {
        this.nombre = nombre;
    }

    public String getNombre() { return nombre; }
    public String value() { return name(); }

    public static ProtocolType fromRequestValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("El protocolo es obligatorio");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("CLINICO".equals(normalized) || "CLINICO_QUIRURGICO".equals(normalized)) {
            return CLINICO_QUIRURGICO;
        }
        if ("DOMESTICO".equals(normalized)) {
            return DOMESTICO;
        }
        throw new IllegalArgumentException("Protocolo no soportado: " + raw);
    }
}
