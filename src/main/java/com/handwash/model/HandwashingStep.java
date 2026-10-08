package com.handwash.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public enum HandwashingStep {
    PASO_1_PALMAS("Paso1_Palmas", "Palmas", 1),
    PASO_2_DORSOS("Paso2_Dorsos", "Dorsos", 2),
    PASO_3_INTERDIGITALES("Paso3_Interdigitales", "Interdigitales", 3),
    PASO_4_NUDILLOS("Paso4_Nudillos", "Nudillos", 4),
    PASO_5_PULGAR("Paso5_Pulgar", "Pulgar", 5),
    PASO_6_PUNTA_DE_DEDOS("Paso6_PuntaDeDedos", "Punta de Dedos", 6),
    PASO_7_CIRCULARES("Paso7_Circulares", "Circulares", 7),
    FONDO("Fondo", "Fondo", 0);

    private final String claseModelo;
    private final String nombre;
    private final int numero;

    private static final Map<String, HandwashingStep> LOOKUP = new HashMap<>();

    static {
        for (HandwashingStep paso : values()) {
            LOOKUP.put(paso.claseModelo, paso);
            LOOKUP.put(paso.name(), paso);
            LOOKUP.put(normalize(paso.claseModelo), paso);
            LOOKUP.put(normalize(paso.name()), paso);
            if (paso.numero >= 1 && paso.numero <= 7) {
                String aliasModelo = "paso_" + paso.numero;
                LOOKUP.put(aliasModelo, paso);
                LOOKUP.put(normalize(aliasModelo), paso);
            }
        }
        LOOKUP.put("PASO_3_PALMA_DORSO_DEDOS", PASO_3_INTERDIGITALES);
        LOOKUP.put("Paso3_PalmaDorsoDedos", PASO_3_INTERDIGITALES);
        LOOKUP.put("PASO_6_PUNTA_DE_DEDOS", PASO_6_PUNTA_DE_DEDOS);
        LOOKUP.put("Paso6_Puntas", PASO_6_PUNTA_DE_DEDOS);
        LOOKUP.put(normalize("PASO_3_PALMA_DORSO_DEDOS"), PASO_3_INTERDIGITALES);
        LOOKUP.put(normalize("Paso3_PalmaDorsoDedos"), PASO_3_INTERDIGITALES);
        LOOKUP.put(normalize("PASO_6_PUNTA_DE_DEDOS"), PASO_6_PUNTA_DE_DEDOS);
        LOOKUP.put(normalize("Paso6_Puntas"), PASO_6_PUNTA_DE_DEDOS);
    }

    HandwashingStep(String claseModelo, String nombre, int numero) {
        this.claseModelo = claseModelo;
        this.nombre = nombre;
        this.numero = numero;
    }

    public String getClaseModelo() { return claseModelo; }
    public String getNombre() { return nombre; }
    public int getNumero() { return numero; }

    public static HandwashingStep fromClaseModelo(String clase) {
        if (clase == null) {
            return null;
        }
        HandwashingStep exacto = LOOKUP.get(clase);
        if (exacto != null) return exacto;
        return LOOKUP.get(normalize(clase));
    }

    public HandwashingStep siguiente() {
        if (this == FONDO || this == PASO_7_CIRCULARES) return null;
        return values()[ordinal() + 1];
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
}
