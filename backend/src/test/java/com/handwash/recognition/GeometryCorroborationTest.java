package com.handwash.recognition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryCorroborationTest {
    private static GeometryShadowSnapshot snapshot(String status, String agreement) {
        return new GeometryShadowSnapshot(status, "PALM_TO_PALM", 0.8, 0.3, "test", 5, 900L, 0.9,
            agreement, List.of(), false);
    }

    @Test
    void disabledNeverVetoes() {
        assertTrue(new GeometryCorroboration(false).veto(snapshot("CANDIDATE", "DISAGREES")).isEmpty());
    }

    @Test
    void aSustainedGeometricCandidateThatContradictsTheClassifierVetoes() {
        Optional<String> veto = new GeometryCorroboration(true).veto(snapshot("CANDIDATE", "DISAGREES"));
        assertEquals("La geometría de las manos contradice al clasificador; confirmando el paso", veto.orElseThrow());
    }

    @Test
    void agreementOrAbstentionKeepsTheCurrentCriterion() {
        GeometryCorroboration on = new GeometryCorroboration(true);
        for (String agreement : List.of("AGREES", "NO_COMPARABLE_PRODUCER_CLASS", "NO_GEOMETRIC_CANDIDATE", "UNAVAILABLE")) {
            assertTrue(on.veto(snapshot("CANDIDATE", agreement)).isEmpty(), agreement);
        }
        for (String status : List.of("ABSTAIN", "AMBIGUOUS")) {
            assertTrue(on.veto(snapshot(status, "DISAGREES")).isEmpty(), status);
        }
    }

    @Test
    void aMissingSnapshotNeverVetoes() {
        assertTrue(new GeometryCorroboration(true).veto(null).isEmpty());
    }
}
