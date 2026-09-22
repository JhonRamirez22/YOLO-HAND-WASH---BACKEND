package com.handwash.recognition;

import java.util.List;

/** Diagnostic-only proposal. It is intentionally incapable of authorizing a wash step. */
public record GeometryShadowSnapshot(String status, String proposedMovement,
                                     double evidenceScore, double scoreMargin,
                                     String reason, int validObservations,
                                     long windowDurationMs, double keypointQuality,
                                     String producerAgreement, List<Candidate> alternatives,
                                     boolean clinicalDecisionAllowed) {
    public GeometryShadowSnapshot {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        clinicalDecisionAllowed = false;
    }

    public record Candidate(String movement, double evidenceScore, String evidence) {}
}
