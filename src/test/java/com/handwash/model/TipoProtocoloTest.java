package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TipoProtocoloTest {

    @Test
    void mapsLegacyClinicoToCanonicalClinicalProtocol() {
        assertEquals(TipoProtocolo.CLINICO_QUIRURGICO, TipoProtocolo.fromRequestValue("CLINICO"));
        assertEquals(TipoProtocolo.CLINICO_QUIRURGICO, TipoProtocolo.fromRequestValue("CLINICO_QUIRURGICO"));
    }

    @Test
    void rejectsMissingProtocolInsteadOfSilentlyChoosingClinicalRules() {
        assertThrows(IllegalArgumentException.class,
            () -> TipoProtocolo.fromRequestValue(null));
        assertThrows(IllegalArgumentException.class,
            () -> TipoProtocolo.fromRequestValue(" "));
    }

    @Test
    void protocolLabelsDoNotClaimSurgicalOrCompleteWhoValidation() {
        assertEquals("Secuencia de 7 movimientos (objetivo 60 s; evaluación parcial)",
            TipoProtocolo.CLINICO_QUIRURGICO.getNombre());
        assertEquals("Secuencia de 7 movimientos (objetivo 40 s; evaluación parcial)",
            TipoProtocolo.DOMESTICO.getNombre());
    }
}
