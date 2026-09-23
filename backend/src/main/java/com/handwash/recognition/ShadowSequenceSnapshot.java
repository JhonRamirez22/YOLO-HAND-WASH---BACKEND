package com.handwash.recognition;

/** Diagnostic view of the shadow decoder; explicitly not an accredited decision. */
public record ShadowSequenceSnapshot(String version, String resultado, String pasoDecodificado,
                                      double probabilidad, String pasoCascada,
                                      ShadowSequenceComparison comparacion, int observaciones,
                                      boolean acreditaPasos) {}
