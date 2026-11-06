package com.handwash.recognition;

import com.handwash.model.WashStep;
import org.junit.jupiter.api.Test;

import static com.handwash.recognition.SequenceDecoderFixtures.distribution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentTemporalDecoderTest {
    private static final int PALMAS = 1;
    private static final int DORSOS = 2;
    private static final int NO_LAVADO = 8;

    @Test
    void abstainsUntilItHasTemporalContext() {
        PersistentTemporalDecoder decoder = new PersistentTemporalDecoder();

        assertEquals(DecoderDecision.Resultado.ABSTENCION,
            decoder.observar(distribution(PALMAS, 0.9), 0).resultado());
        assertEquals(WashStep.PASO_1_PALMAS, decoder.observar(distribution(PALMAS, 0.9), 200).paso());
    }

    @Test
    void accumulatesConsistentEvidenceThatEachFrameAloneLeavesBelowTheCascadeThreshold() {
        PersistentTemporalDecoder decoder = new PersistentTemporalDecoder();
        DecoderDecision decision = null;
        for (int frame = 0; frame < 4; frame++) {
            decision = decoder.observar(distribution(PALMAS, 0.45), frame * 200L);
        }

        assertEquals(WashStep.PASO_1_PALMAS, decision.paso(),
            "0.45 per frame never passes the producer's 0.75 gate, but is consistent over time");
        assertTrue(decision.probabilidad() > 0.6);
    }

    @Test
    void singleContradictoryFrameDoesNotFlipButASustainedChangeDoes() {
        PersistentTemporalDecoder decoder = new PersistentTemporalDecoder();
        long t = 0;
        for (int frame = 0; frame < 5; frame++, t += 200) decoder.observar(distribution(PALMAS, 0.8), t);

        assertEquals(WashStep.PASO_1_PALMAS, decoder.observar(distribution(DORSOS, 0.7), t).paso());
        DecoderDecision decision = null;
        for (int frame = 0; frame < 3; frame++) {
            t += 200;
            decision = decoder.observar(distribution(DORSOS, 0.8), t);
        }
        assertEquals(WashStep.PASO_2_DORSOS, decision.paso());
    }

    @Test
    void notWashingEvidenceYieldsNoStepAndLongGapsResetContext() {
        PersistentTemporalDecoder decoder = new PersistentTemporalDecoder();
        decoder.observar(distribution(NO_LAVADO, 0.9), 0);
        assertEquals(DecoderDecision.Resultado.SIN_PASO,
            decoder.observar(distribution(NO_LAVADO, 0.9), 200).resultado());

        assertEquals(DecoderDecision.Resultado.ABSTENCION,
            decoder.observar(distribution(PALMAS, 0.9), 1_500).resultado(),
            "a gap above 1 s restarts the context instead of bridging it");
        assertEquals(DecoderDecision.Resultado.ABSTENCION,
            decoder.observar(distribution(PALMAS, 0.9), 1_400).resultado(),
            "time going backwards also restarts the context");
    }

    @Test
    void alternatingEvidenceNeverProducesAStep() {
        PersistentTemporalDecoder decoder = new PersistentTemporalDecoder();
        for (int frame = 0; frame < 8; frame++) {
            DecoderDecision decision =
                decoder.observar(distribution(frame % 2 == 0 ? PALMAS : DORSOS, 0.4), frame * 200L);
            assertEquals(null, decision.paso(), "frame " + frame);
        }
    }
}
