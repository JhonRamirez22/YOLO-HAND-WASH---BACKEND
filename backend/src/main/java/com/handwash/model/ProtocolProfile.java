package com.handwash.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, versioned soap-and-water friction profile. Times are project rules
 * reviewed by an ADMIN; they are not per-movement WHO thresholds.
 */
public record ProtocolProfile(int version, Map<WashStep, Long> tiemposPasoMs,
                              long umbralLentoMs, String creadoPor, long creadoEnEpochMs) {
    public static final List<WashStep> PASOS = List.of(
        WashStep.PASO_1_PALMAS, WashStep.PASO_2_DORSOS,
        WashStep.PASO_3_INTERDIGITALES, WashStep.PASO_4_NUDILLOS,
        WashStep.PASO_5_PULGAR, WashStep.PASO_6_PUNTA_DE_DEDOS);
    private static final Set<WashStep> PASOS_REQUERIDOS = Set.copyOf(PASOS);
    public static final long MIN_PASO_MS = 3_000L;
    public static final long MAX_PASO_MS = 15_000L;
    public static final long MIN_TOTAL_MS = 40_000L;
    public static final long MAX_TOTAL_MS = 60_000L;
    public static final long MAX_UMBRAL_LENTO_MS = 120_000L;
    public static final String SISTEMA = "SYSTEM";

    /** Version 1, seeded by migration V7: 7 s per movement, 42 s of friction. */
    public static final ProtocolProfile PREDETERMINADO =
        new ProtocolProfile(1, uniforme(7_000L), 60_000L, SISTEMA, 0L);

    public ProtocolProfile {
        if (version < 1) throw new IllegalArgumentException("La versión del perfil debe ser positiva");
        Objects.requireNonNull(tiemposPasoMs, "tiemposPasoMs");
        if (!tiemposPasoMs.keySet().equals(PASOS_REQUERIDOS)) {
            throw new IllegalArgumentException("El perfil debe definir exactamente los seis movimientos");
        }
        long total = 0L;
        for (WashStep paso : PASOS) {
            Long tiempo = tiemposPasoMs.get(paso);
            if (tiempo == null || tiempo < MIN_PASO_MS || tiempo > MAX_PASO_MS) {
                throw new IllegalArgumentException("Tiempo fuera de rango para " + paso.name());
            }
            total += tiempo;
        }
        if (total < MIN_TOTAL_MS || total > MAX_TOTAL_MS) {
            throw new IllegalArgumentException("La suma de movimientos debe estar entre 40 y 60 s");
        }
        if (umbralLentoMs < total || umbralLentoMs > MAX_UMBRAL_LENTO_MS) {
            throw new IllegalArgumentException("El umbral de lavado lento es inválido");
        }
        if (creadoPor == null || creadoPor.isBlank() || creadoPor.length() > 128) {
            throw new IllegalArgumentException("El autor del perfil es obligatorio");
        }
        if (creadoEnEpochMs < 0L) throw new IllegalArgumentException("Fecha de creación inválida");
        EnumMap<WashStep, Long> copia = new EnumMap<>(WashStep.class);
        copia.putAll(tiemposPasoMs);
        tiemposPasoMs = Collections.unmodifiableMap(copia);
    }

    public long duracionTotalMs() {
        return tiemposPasoMs.values().stream().mapToLong(Long::longValue).sum();
    }

    public ProtocolProfile siguienteVersion(Map<WashStep, Long> tiempos, long umbralLento,
                                            String autor, long ahoraEpochMs) {
        return new ProtocolProfile(Math.addExact(version, 1), tiempos, umbralLento, autor, ahoraEpochMs);
    }

    public static Map<WashStep, Long> uniforme(long tiempoMs) {
        EnumMap<WashStep, Long> tiempos = new EnumMap<>(WashStep.class);
        PASOS.forEach(paso -> tiempos.put(paso, tiempoMs));
        return tiempos;
    }
}
