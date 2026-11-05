package com.handwash.recognition;

import com.handwash.model.WashStep;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClassifierDistributionTest {
    @Test
    void foldsNegativeClassesIntoNoMovementMass() {
        ClassifierDistribution d = ClassifierDistribution.desde(SequenceDecoderFixtures.wire(8, 0.6));

        assertNotNull(d);
        assertEquals(0.6 + 2 * 0.05, d.sinPaso(), 1e-9);
        assertEquals(0.05, d.pasos().get(WashStep.PASO_1_PALMAS), 1e-9);
        assertEquals(6, d.pasos().size());
    }

    @Test
    void rejectsAnythingButTheExactNormalisedNineClassMap() {
        Map<String, Double> missing = new HashMap<>(SequenceDecoderFixtures.wire(1, 0.6));
        missing.remove("08_not_washing");
        Map<String, Double> renamed = new HashMap<>(missing);
        renamed.put("07_paso_circulares", 0.05);
        Map<String, Double> notNormalised = new HashMap<>(SequenceDecoderFixtures.wire(1, 0.6));
        notNormalised.put("01_palm_to_palm", 0.9);
        Map<String, Double> notFinite = new HashMap<>(SequenceDecoderFixtures.wire(1, 0.6));
        notFinite.put("02_palm_over_dorsum_fingers_interlaced", Double.NaN);

        assertNull(ClassifierDistribution.desde(null));
        assertNull(ClassifierDistribution.desde(missing));
        assertNull(ClassifierDistribution.desde(renamed));
        assertNull(ClassifierDistribution.desde(notNormalised));
        assertNull(ClassifierDistribution.desde(notFinite));
    }
}
