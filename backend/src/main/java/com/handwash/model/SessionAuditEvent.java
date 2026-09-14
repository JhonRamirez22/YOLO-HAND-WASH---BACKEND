package com.handwash.model;

/** Metadata-only audit fact; it deliberately has no image, video, token, or free-form payload. */
public record SessionAuditEvent(
    long eventId,
    String sessionId,
    String eventType,
    String actorSubject,
    String stepCode,
    String decisionCode,
    String reasonCode,
    Integer attemptNumber,
    Long frameSequence,
    Double confidence,
    long occurredAtEpochMs
) {}
