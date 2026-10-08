package com.handwash.strategy;

import com.handwash.model.PasoLavado;
import java.util.Map;

public class DomesticoStrategy implements ReglaValidacionStrategy {

    private static final Map<PasoLavado, Long> TIEMPOS = Map.of(
        PasoLavado.PASO_1_PALMAS, 6000L,
        PasoLavado.PASO_2_DORSOS, 6000L,
        PasoLavado.PASO_3_INTERDIGITALES, 6000L,
        PasoLavado.PASO_4_NUDILLOS, 6000L,
        PasoLavado.PASO_5_PULGAR, 4000L,
        PasoLavado.PASO_6_PUNTA_DE_DEDOS, 4000L,
        PasoLavado.PASO_7_CIRCULARES, 8000L
    );

    @Override
    public boolean validarTiempoPaso(PasoLavado paso, long tiempoAcumuladoMs) {
        return tiempoAcumuladoMs >= getTiempoRequeridoPaso(paso);
    }

    @Override
    public long getTiempoRequeridoPaso(PasoLavado paso) {
        return TIEMPOS.getOrDefault(paso, 6000L);
    }

    @Override
    public long getDuracionTotalMs() { return 40000L; }
}
