package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PasoLavadoTest {
    @Test
    void mapsEveryActiveDetectorClassToItsCanonicalStep() {
        assertEquals(PasoLavado.PASO_1_PALMAS, PasoLavado.fromClaseModelo("Paso1_Palmas"));
        assertEquals(PasoLavado.PASO_2_DORSOS, PasoLavado.fromClaseModelo("Paso2_Dorsos"));
        assertEquals(PasoLavado.PASO_3_INTERDIGITALES, PasoLavado.fromClaseModelo("Paso3_Interdigitales"));
        assertEquals(PasoLavado.PASO_4_NUDILLOS, PasoLavado.fromClaseModelo("Paso4_Nudillos"));
        assertEquals(PasoLavado.PASO_5_PULGAR, PasoLavado.fromClaseModelo("Paso5_Pulgar"));
        assertEquals(PasoLavado.PASO_6_PUNTA_DE_DEDOS, PasoLavado.fromClaseModelo("Paso6_PuntaDeDedos"));
        assertEquals(PasoLavado.PASO_7_CIRCULARES, PasoLavado.fromClaseModelo("Paso7_Circulares"));
        assertEquals(PasoLavado.FONDO, PasoLavado.fromClaseModelo("Fondo"));
    }

    @Test
    void backgroundIsNeverTheNextProtocolStep() {
        assertEquals(PasoLavado.PASO_7_CIRCULARES,
            PasoLavado.PASO_6_PUNTA_DE_DEDOS.siguiente());
        assertNull(PasoLavado.PASO_7_CIRCULARES.siguiente());
        assertNull(PasoLavado.FONDO.siguiente());
    }

    @Test
    void unknownAnatomicalLabelsAreNotSilentlyMappedToAnotherMovement() {
        assertNull(PasoLavado.fromClaseModelo("MUNECAS"));
    }
}
