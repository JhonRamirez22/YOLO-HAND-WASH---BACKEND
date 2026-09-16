package com.handwash.strategy;

import com.handwash.model.WashStep;
import java.util.Map;

public class DomesticStrategy implements ValidationRuleStrategy {

    private static final Map<WashStep, Long> TIEMPOS = Map.of(
        WashStep.PASO_1_PALMAS, 6000L,
        WashStep.PASO_2_DORSOS, 6000L,
        WashStep.PASO_3_INTERDIGITALES, 6000L,
        WashStep.PASO_4_NUDILLOS, 6000L,
        WashStep.PASO_5_PULGAR, 4000L,
        WashStep.PASO_6_PUNTA_DE_DEDOS, 4000L,
        WashStep.PASO_7_CIRCULARES, 8000L
    );

    @Override
    public boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs) {
        return tiempoAcumuladoMs >= getTiempoRequeridoPaso(paso);
    }

    @Override
    public long getTiempoRequeridoPaso(WashStep paso) {
        return TIEMPOS.getOrDefault(paso, 6000L);
    }

    @Override
    public long getDuracionTotalMs() { return 40000L; }
}
