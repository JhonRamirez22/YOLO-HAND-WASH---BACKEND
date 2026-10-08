package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolTypeTest {

    @Test
    void mapsLegacyClinicoToCanonicalClinicalProtocol() {
        assertEquals(ProtocolType.CLINICO_QUIRURGICO, ProtocolType.fromRequestValue("CLINICO"));
        assertEquals(ProtocolType.CLINICO_QUIRURGICO, ProtocolType.fromRequestValue("CLINICO_QUIRURGICO"));
    }

    @Test
    void rejectsMissingProtocolInsteadOfSilentlyChoosingClinicalRules() {
        assertThrows(IllegalArgumentException.class,
            () -> ProtocolType.fromRequestValue(null));
        assertThrows(IllegalArgumentException.class,
            () -> ProtocolType.fromRequestValue(" "));
    }

    @Test
    void protocolLabelsDoNotClaimSurgicalOrCompleteWhoValidation() {
        assertEquals("Secuencia de 7 movimientos (objetivo 60 s; evaluación parcial)",
            ProtocolType.CLINICO_QUIRURGICO.getNombre());
        assertEquals("Secuencia de 7 movimientos (objetivo 40 s; evaluación parcial)",
            ProtocolType.DOMESTICO.getNombre());
    }
}
