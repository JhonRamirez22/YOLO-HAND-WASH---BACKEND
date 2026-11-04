package com.handwash.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolProfileTest {
    @Test
    void defaultProfileIsSevenSecondsPerMovementWithinTheSoapAndWaterRange() {
        ProtocolProfile perfil = ProtocolProfile.PREDETERMINADO;

        assertEquals(1, perfil.version());
        assertEquals(6, perfil.tiemposPasoMs().size());
        perfil.tiemposPasoMs().values().forEach(tiempo -> assertEquals(7_000L, tiempo));
        assertEquals(42_000L, perfil.duracionTotalMs());
        assertEquals(60_000L, perfil.umbralLentoMs());
    }

    @Test
    void rejectsProfilesOutsideTheReviewedRanges() {
        assertThrows(IllegalArgumentException.class, () -> perfil(ProtocolProfile.uniforme(6_000L), 60_000L),
            "36 s total is below the 40 s soap-and-water minimum");
        assertThrows(IllegalArgumentException.class, () -> perfil(ProtocolProfile.uniforme(11_000L), 90_000L),
            "66 s total exceeds 60 s");
        Map<WashStep, Long> pasoCorto = new EnumMap<>(ProtocolProfile.uniforme(8_000L));
        pasoCorto.put(WashStep.PASO_5_PULGAR, 2_000L);
        assertThrows(IllegalArgumentException.class, () -> perfil(pasoCorto, 60_000L));
        Map<WashStep, Long> sinYemas = new EnumMap<>(ProtocolProfile.uniforme(8_000L));
        sinYemas.remove(WashStep.PASO_6_PUNTA_DE_DEDOS);
        assertThrows(IllegalArgumentException.class, () -> perfil(sinYemas, 60_000L));
        Map<WashStep, Long> conPaso7 = new EnumMap<>(ProtocolProfile.uniforme(7_000L));
        conPaso7.put(WashStep.PASO_7_CIRCULARES, 7_000L);
        assertThrows(IllegalArgumentException.class, () -> perfil(conPaso7, 60_000L));
        assertThrows(IllegalArgumentException.class, () -> perfil(ProtocolProfile.uniforme(7_000L), 41_000L),
            "slow threshold below the required friction time");
    }

    @Test
    void nextVersionIsImmutableAndCopiesTheGivenTimes() {
        Map<WashStep, Long> tiempos = new EnumMap<>(ProtocolProfile.uniforme(8_000L));
        ProtocolProfile siguiente = ProtocolProfile.PREDETERMINADO
            .siguienteVersion(tiempos, 70_000L, "admin-sub", 1_000L);
        tiempos.put(WashStep.PASO_1_PALMAS, 15_000L);

        assertEquals(2, siguiente.version());
        assertEquals(8_000L, siguiente.tiemposPasoMs().get(WashStep.PASO_1_PALMAS));
        assertThrows(UnsupportedOperationException.class,
            () -> siguiente.tiemposPasoMs().put(WashStep.PASO_1_PALMAS, 3_000L));
    }

    private static ProtocolProfile perfil(Map<WashStep, Long> tiempos, long umbral) {
        return new ProtocolProfile(2, tiempos, umbral, "admin-sub", 1L);
    }
}
