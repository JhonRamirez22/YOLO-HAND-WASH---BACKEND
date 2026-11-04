package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WashStepTest {
    @Test
    void mapsEveryActiveDetectorClassToItsCanonicalStep() {
        assertEquals(WashStep.PASO_1_PALMAS, WashStep.fromClaseModelo("Paso1_Palmas"));
        assertEquals(WashStep.PASO_2_DORSOS, WashStep.fromClaseModelo("Paso2_Dorsos"));
        assertEquals(WashStep.PASO_3_INTERDIGITALES, WashStep.fromClaseModelo("Paso3_Interdigitales"));
        assertEquals(WashStep.PASO_4_NUDILLOS, WashStep.fromClaseModelo("Paso4_Nudillos"));
        assertEquals(WashStep.PASO_5_PULGAR, WashStep.fromClaseModelo("Paso5_Pulgar"));
        assertEquals(WashStep.PASO_6_PUNTA_DE_DEDOS, WashStep.fromClaseModelo("Paso6_PuntaDeDedos"));
        assertEquals(WashStep.PASO_7_CIRCULARES, WashStep.fromClaseModelo("Paso7_Circulares"));
        assertEquals(WashStep.FONDO, WashStep.fromClaseModelo("Fondo"));
    }

    @Test
    void backgroundIsNeverTheNextProtocolStep() {
        assertNull(WashStep.PASO_6_PUNTA_DE_DEDOS.siguiente());
        assertNull(WashStep.PASO_7_CIRCULARES.siguiente());
        assertNull(WashStep.FONDO.siguiente());
        assertEquals(6, WashStep.getMovimientosFriccionSoportados());
    }

    @Test
    void unknownAnatomicalLabelsAreNotSilentlyMappedToAnotherMovement() {
        assertNull(WashStep.fromClaseModelo("MUNECAS"));
    }
}
