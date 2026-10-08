package com.handwash.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ordered observable actions from the WHO soap-and-water handwashing sequence. */
public enum OmsAction {
    MOJAR_MANOS("OMS_01_MOJAR_MANOS", "Mojar las manos", 1),
    APLICAR_JABON("OMS_02_APLICAR_JABON", "Aplicar jabón", 2),
    FROTAR_PALMAS("OMS_03_FROTAR_PALMAS", "Frotar palma con palma", 3),
    FROTAR_DORSOS("OMS_04_FROTAR_DORSOS", "Frotar dorsos con dedos intercalados", 4),
    FROTAR_ENTRE_DEDOS("OMS_05_FROTAR_ENTRE_DEDOS", "Frotar palmas con dedos intercalados", 5),
    FROTAR_DORSO_DE_DEDOS("OMS_06_FROTAR_DORSO_DE_DEDOS", "Frotar dorsos de los dedos", 6),
    FROTAR_PULGARES("OMS_07_FROTAR_PULGARES", "Frotar cada pulgar", 7),
    FROTAR_PUNTAS_DE_DEDOS("OMS_08_FROTAR_PUNTAS_DE_DEDOS", "Frotar puntas de los dedos", 8),
    ENJUAGAR_MANOS("OMS_09_ENJUAGAR_MANOS", "Enjuagar las manos", 9),
    SECAR_TOALLA_DESECHABLE("OMS_10_SECAR_TOALLA_DESECHABLE", "Secar con toalla desechable", 10),
    CERRAR_GRIFO_CON_TOALLA("OMS_11_CERRAR_GRIFO_CON_TOALLA", "Cerrar el grifo con la toalla", 11),
    CONTACTO_RIESGO("OMS_CONTACTO_RIESGO", "Contacto de riesgo visible", -1),
    SIN_EVIDENCIA("OMS_SIN_EVIDENCIA", "Manos fuera de cuadro o acción no visible", 0);

    public static final List<OmsAction> SECUENCIA = Arrays.stream(values())
        .filter(accion -> accion.orden > 0)
        .toList();

    private static final Map<String, OmsAction> LOOKUP = crearLookup();

    private final String claseModelo;
    private final String nombre;
    private final int orden;

    OmsAction(String claseModelo, String nombre, int orden) {
        this.claseModelo = claseModelo;
        this.nombre = nombre;
        this.orden = orden;
    }

    public String getClaseModelo() { return claseModelo; }
    public String getNombre() { return nombre; }
    public int getOrden() { return orden; }
    /** Human-facing instructions; the class label alone is not a clinical instruction. */
    public String getInstruccion() {
        return switch (this) {
            case MOJAR_MANOS -> "Mojar las manos con agua.";
            case APLICAR_JABON -> "Aplicar jabón suficiente para cubrir todas las superficies de ambas manos.";
            case FROTAR_PALMAS -> "Frotar palma con palma.";
            case FROTAR_DORSOS -> "Frotar la palma derecha sobre el dorso izquierdo con dedos entrelazados, y viceversa.";
            case FROTAR_ENTRE_DEDOS -> "Frotar palma con palma con los dedos entrelazados.";
            case FROTAR_DORSO_DE_DEDOS -> "Frotar el dorso de los dedos contra la palma opuesta, agarrando los dedos.";
            case FROTAR_PULGARES -> "Frotar cada pulgar con movimientos de rotación en la palma opuesta.";
            case FROTAR_PUNTAS_DE_DEDOS -> "Frotar las puntas de los dedos de una mano contra la palma opuesta con movimiento de rotación, y viceversa.";
            case ENJUAGAR_MANOS -> "Enjuagar las manos con agua.";
            case SECAR_TOALLA_DESECHABLE -> "Secar las manos completamente con una toalla de un solo uso.";
            case CERRAR_GRIFO_CON_TOALLA -> "Usar la toalla para cerrar el grifo.";
            case CONTACTO_RIESGO, SIN_EVIDENCIA -> "Señal de control; no es una fase del lavado.";
        };
    }
    public boolean esRiesgo() { return this == CONTACTO_RIESGO; }
    public boolean esSinEvidencia() { return this == SIN_EVIDENCIA; }
    public boolean permiteEvidenciaJabon() { return orden >= 2 && orden <= 8; }

    public OmsAction siguiente() {
        if (esRiesgo() || orden < 1 || orden >= SECUENCIA.size()) return null;
        return SECUENCIA.get(orden);
    }

    public static OmsAction fromClaseModelo(String value) {
        if (value == null) return null;
        OmsAction exacta = LOOKUP.get(value);
        return exacta != null ? exacta : LOOKUP.get(normalize(value));
    }

    private static Map<String, OmsAction> crearLookup() {
        Map<String, OmsAction> lookup = new HashMap<>();
        for (OmsAction accion : values()) {
            lookup.put(accion.claseModelo, accion);
            lookup.put(accion.name(), accion);
        }
        return Map.copyOf(lookup);
    }

    private static String normalize(String value) {
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
}
