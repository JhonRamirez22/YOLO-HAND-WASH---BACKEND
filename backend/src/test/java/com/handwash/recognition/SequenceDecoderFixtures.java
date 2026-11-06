package com.handwash.recognition;

import java.util.LinkedHashMap;
import java.util.Map;

final class SequenceDecoderFixtures {
    private SequenceDecoderFixtures() {}

    /** Wire map with {@code peak} on class index {@code classIndex} and the rest spread evenly. */
    static Map<String, Double> wire(int classIndex, double peak) {
        Map<String, Double> wire = new LinkedHashMap<>();
        double rest = (1.0 - peak) / (ClassifierDistribution.CLASES.size() - 1);
        for (int i = 0; i < ClassifierDistribution.CLASES.size(); i++) {
            wire.put(ClassifierDistribution.CLASES.get(i), i == classIndex ? peak : rest);
        }
        return wire;
    }

    static ClassifierDistribution distribution(int classIndex, double peak) {
        return ClassifierDistribution.desde(wire(classIndex, peak));
    }
}
