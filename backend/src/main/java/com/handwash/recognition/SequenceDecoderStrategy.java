package com.handwash.recognition;

/**
 * Strategy that turns a stream of classifier distributions into a smoothed movement
 * estimate. Implementations are shadow-only: they never feed State, Strategy or audit.
 */
public interface SequenceDecoderStrategy {
    String version();

    DecoderDecision observar(ClassifierDistribution distribucion, long instanteMs);

    /** Forget temporal context, e.g. after losing both hands or a producer reset. */
    void reiniciar();
}
