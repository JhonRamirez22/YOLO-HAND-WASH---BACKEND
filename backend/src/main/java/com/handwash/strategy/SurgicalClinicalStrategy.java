package com.handwash.strategy;

import com.handwash.model.WashStep;
import java.util.Map;

public class SurgicalClinicalStrategy implements ValidationRuleStrategy {

    private static final Map<WashStep, Long> TIEMPOS = Map.of(
        WashStep.PASO_1_PALMAS, 8000L,
        WashStep.PASO_2_DORSOS, 8000L,
        WashStep.PASO_3_INTERDIGITALES, 8000L,
        WashStep.PASO_4_NUDILLOS, 8000L,
        WashStep.PASO_5_PULGAR, 8000L,
        WashStep.PASO_6_PUNTA_DE_DEDOS, 8000L,
        WashStep.PASO_7_CIRCULARES, 12000L
    );

    @Override
    public boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs) {
        return tiempoAcumuladoMs >= getTiempoRequeridoPaso(paso);
    }

    @Override
    public long getTiempoRequeridoPaso(WashStep paso) {
        return TIEMPOS.getOrDefault(paso, 8000L);
    }

    @Override
    public long getDuracionTotalMs() { return 60000L; }
}
