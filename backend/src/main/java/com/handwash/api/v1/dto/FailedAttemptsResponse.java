package com.handwash.api.v1.dto;

import java.util.List;

/** Bounded metadata-only history returned for restarted attempts. */
public record FailedAttemptsResponse(
    String sessionId,
    List<FailedAttemptResponse> intentosFallidos,
    boolean soloMetadatos
) {
    public FailedAttemptsResponse {
        intentosFallidos = List.copyOf(intentosFallidos);
    }
}
