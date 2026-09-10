package com.handwash.model;

/** Read-only administrator projection; excludes the stored JSON payload and violation text. */
public record FailedAttemptAuditRecord(
    String sessionId,
    int attemptNumber,
    String result,
    String reasonCode,
    long durationMs,
    long createdAtEpochMs
) {}
