package com.handwash.recognition;

import com.handwash.model.WashStep;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validated nine-class distribution of the active classifier, folded into the six
 * friction movements plus one "no movement" mass (other, faucet, not washing).
 */
public record ClassifierDistribution(Map<WashStep, Double> pasos, double sinPaso) {
    public static final List<String> CLASES = List.of(
        "00_other_washing_movement", "01_palm_to_palm",
        "02_palm_over_dorsum_fingers_interlaced", "03_palms_fingers_interlaced",
        "04_backs_of_fingers_interlocked", "05_rotational_thumb_rubbing",
        "06_fingertips_to_palm", "07_turn_off_faucet_with_paper_towel", "08_not_washing");
    public static final List<WashStep> PASOS = List.of(
        WashStep.PASO_1_PALMAS, WashStep.PASO_2_DORSOS, WashStep.PASO_3_INTERDIGITALES,
        WashStep.PASO_4_NUDILLOS, WashStep.PASO_5_PULGAR, WashStep.PASO_6_PUNTA_DE_DEDOS);
    private static final double TOLERANCIA_SUMA = 0.02;

    public ClassifierDistribution {
        pasos = Collections.unmodifiableMap(new EnumMap<>(pasos));
    }

    /** Returns null for anything but the exact nine-class, normalised wire map. */
    public static ClassifierDistribution desde(Map<String, Double> wire) {
        if (wire == null || wire.size() != CLASES.size() || !wire.keySet().containsAll(CLASES)) return null;
        double suma = 0.0;
        for (String clase : CLASES) {
            Double valor = wire.get(clase);
            if (valor == null || !Double.isFinite(valor) || valor < 0.0 || valor > 1.0) return null;
            suma += valor;
        }
        if (Math.abs(suma - 1.0) > TOLERANCIA_SUMA) return null;
        EnumMap<WashStep, Double> pasos = new EnumMap<>(WashStep.class);
        for (int i = 0; i < PASOS.size(); i++) pasos.put(PASOS.get(i), wire.get(CLASES.get(i + 1)) / suma);
        double sinPaso = (wire.get(CLASES.get(0)) + wire.get(CLASES.get(7)) + wire.get(CLASES.get(8))) / suma;
        return new ClassifierDistribution(pasos, sinPaso);
    }
}
