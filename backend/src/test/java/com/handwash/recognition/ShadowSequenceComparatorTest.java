package com.handwash.recognition;

import com.handwash.model.WashStep;
import org.junit.jupiter.api.Test;

import static com.handwash.recognition.SequenceDecoderFixtures.distribution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShadowSequenceComparatorTest {
    @Test
    void classifiesAgreementAgainstAFreshCascadeProposal() {
        ShadowSequenceComparator comparator = new ShadowSequenceComparator(new PersistentTemporalDecoder());
        comparator.observar(distribution(1, 0.9), 0);

        assertEquals(ShadowSequenceComparison.SOLO_DECODIFICADOR,
            comparator.observar(distribution(1, 0.9), 200).comparacion());
        comparator.registrarPropuestaCascada(WashStep.PASO_1_PALMAS, 300);
        ShadowSequenceSnapshot agree = comparator.observar(distribution(1, 0.9), 400);
        assertEquals(ShadowSequenceComparison.COINCIDEN, agree.comparacion());
        assertFalse(agree.acreditaPasos());
        comparator.registrarPropuestaCascada(WashStep.PASO_2_DORSOS, 500);
        assertEquals(ShadowSequenceComparison.DIFIEREN,
            comparator.observar(distribution(1, 0.9), 600).comparacion());
        assertEquals(ShadowSequenceComparison.SOLO_DECODIFICADOR,
            comparator.observar(distribution(1, 0.9), 1_300).comparacion(),
            "a cascade proposal older than 700 ms no longer counts");
    }

    @Test
    void reportsCascadeOnlyAndNeitherAndIgnoresStepSeven() {
        ShadowSequenceComparator comparator = new ShadowSequenceComparator(new PersistentTemporalDecoder());
        comparator.registrarPropuestaCascada(WashStep.PASO_3_INTERDIGITALES, 0);
        assertEquals(ShadowSequenceComparison.SOLO_CASCADA,
            comparator.observar(distribution(8, 0.9), 100).comparacion());

        comparator.reiniciar();
        comparator.registrarPropuestaCascada(WashStep.PASO_7_CIRCULARES, 150);
        ShadowSequenceSnapshot snapshot = comparator.observar(distribution(8, 0.9), 200);
        assertEquals(ShadowSequenceComparison.NINGUNO, snapshot.comparacion());
        assertNull(snapshot.pasoCascada());
    }
}
