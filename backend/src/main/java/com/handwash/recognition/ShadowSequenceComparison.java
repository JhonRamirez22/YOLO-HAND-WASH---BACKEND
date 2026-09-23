package com.handwash.recognition;

/** Fixed, low-cardinality outcome of comparing the decoder with the producer cascade. */
public enum ShadowSequenceComparison {
    /** Both propose the same movement. */
    COINCIDEN,
    /** Only the decoder proposes a movement: candidate coverage gain to review. */
    SOLO_DECODIFICADOR,
    /** Only the threshold cascade proposes a movement. */
    SOLO_CASCADA,
    /** Both propose different movements. */
    DIFIEREN,
    /** Neither proposes a movement. */
    NINGUNO
}
