package com.handwash.model;

import java.util.List;

/**
 * Durable decision snapshot, keyed by session/attempt, without frames or credentials.
 * Wall-clock times are the exact start/end of the wash; the duration is monotonic.
 */
public record FrictionAttemptRecord(String sessionId, int attemptNumber, String policyVersion,
    long revision, String status, String result, String reason, boolean terminal,
    List<FrictionEvaluation.Step> steps, Long startedAtEpochMs, Long finishedAtEpochMs,
    Long durationMs, boolean slow, WashOutcome outcome, String evidenceStatus, Integer scorePct, String verdict) {
    public FrictionAttemptRecord { steps = List.copyOf(steps); }

    public FrictionAttemptRecord(String sessionId, int attemptNumber, String policyVersion,
        long revision, String status, String result, String reason, boolean terminal,
        List<FrictionEvaluation.Step> steps, Long startedAtEpochMs, Long finishedAtEpochMs,
        Long durationMs, boolean slow, WashOutcome outcome, String evidenceStatus) {
        this(sessionId, attemptNumber, policyVersion, revision, status, result, reason, terminal, steps,
            startedAtEpochMs, finishedAtEpochMs, durationMs, slow, outcome, evidenceStatus, null, null);
    }

    public FrictionAttemptRecord(String sessionId, int attemptNumber, String policyVersion,
                                 long revision, String status, String result, String reason,
                                 boolean terminal, List<FrictionEvaluation.Step> steps) {
        this(sessionId, attemptNumber, policyVersion, revision, status, result, reason, terminal,
            steps, null, null, null, false, null, null, null, null);
    }

    public FrictionAttemptRecord withEvidenceStatus(String status) {
        return new FrictionAttemptRecord(sessionId, attemptNumber, policyVersion, revision, this.status,
            result, reason, terminal, steps, startedAtEpochMs, finishedAtEpochMs, durationMs, slow,
            outcome, status, scorePct, verdict);
    }
}
