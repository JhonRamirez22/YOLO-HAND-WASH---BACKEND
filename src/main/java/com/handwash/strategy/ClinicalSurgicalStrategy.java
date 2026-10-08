package com.handwash.strategy;

import com.handwash.model.HandwashingStep;
import java.util.Map;

public class ClinicalSurgicalStrategy implements ValidationRuleStrategy {

    private static final Map<HandwashingStep, Long> TIEMPOS = Map.of(
        HandwashingStep.PASO_1_PALMAS, 8000L,
        HandwashingStep.PASO_2_DORSOS, 8000L,
        HandwashingStep.PASO_3_INTERDIGITALES, 8000L,
        HandwashingStep.PASO_4_NUDILLOS, 8000L,
        HandwashingStep.PASO_5_PULGAR, 8000L,
        HandwashingStep.PASO_6_PUNTA_DE_DEDOS, 8000L,
        HandwashingStep.PASO_7_CIRCULARES, 12000L
    );

    @Override
    public boolean validarTiempoPaso(HandwashingStep paso, long tiempoAcumuladoMs) {
        return tiempoAcumuladoMs >= getTiempoRequeridoPaso(paso);
    }

    @Override
    public long getTiempoRequeridoPaso(HandwashingStep paso) {
        return TIEMPOS.getOrDefault(paso, 8000L);
    }

    @Override
    public long getDuracionTotalMs() { return 60000L; }
}
