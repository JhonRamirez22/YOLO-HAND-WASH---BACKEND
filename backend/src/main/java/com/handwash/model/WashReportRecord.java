package com.handwash.model;

import java.util.List;

/** One finished attempt for the ADMIN audit: exact times, outcome, per-step result. */
public record WashReportRecord(String sessionId, int attemptNumber, String policyVersion,
                               WashOutcome resultado, String motivo, Long inicioEpochMs,
                               Long finEpochMs, Long duracionMs, boolean lento,
                               String estadoEvidencia, List<FrictionEvaluation.Step> pasos) {
    public WashReportRecord { pasos = List.copyOf(pasos); }
}
