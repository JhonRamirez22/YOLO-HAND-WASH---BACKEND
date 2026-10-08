package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandwashingStepTest {
    @Test
    void mapsEveryActiveDetectorClassToItsCanonicalStep() {
        assertEquals(HandwashingStep.PASO_1_PALMAS, HandwashingStep.fromClaseModelo("Paso1_Palmas"));
        assertEquals(HandwashingStep.PASO_2_DORSOS, HandwashingStep.fromClaseModelo("Paso2_Dorsos"));
        assertEquals(HandwashingStep.PASO_3_INTERDIGITALES, HandwashingStep.fromClaseModelo("Paso3_Interdigitales"));
        assertEquals(HandwashingStep.PASO_4_NUDILLOS, HandwashingStep.fromClaseModelo("Paso4_Nudillos"));
        assertEquals(HandwashingStep.PASO_5_PULGAR, HandwashingStep.fromClaseModelo("Paso5_Pulgar"));
        assertEquals(HandwashingStep.PASO_6_PUNTA_DE_DEDOS, HandwashingStep.fromClaseModelo("Paso6_PuntaDeDedos"));
        assertEquals(HandwashingStep.PASO_7_CIRCULARES, HandwashingStep.fromClaseModelo("Paso7_Circulares"));
        assertEquals(HandwashingStep.FONDO, HandwashingStep.fromClaseModelo("Fondo"));
    }

    @Test
    void backgroundIsNeverTheNextProtocolStep() {
        assertEquals(HandwashingStep.PASO_7_CIRCULARES,
            HandwashingStep.PASO_6_PUNTA_DE_DEDOS.siguiente());
        assertNull(HandwashingStep.PASO_7_CIRCULARES.siguiente());
        assertNull(HandwashingStep.FONDO.siguiente());
    }

    @Test
    void unknownAnatomicalLabelsAreNotSilentlyMappedToAnotherMovement() {
        assertNull(HandwashingStep.fromClaseModelo("MUNECAS"));
    }
}
