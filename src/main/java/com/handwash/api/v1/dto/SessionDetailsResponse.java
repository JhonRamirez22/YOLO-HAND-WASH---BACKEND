package com.handwash.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Versioned REST view of a session; the pairing secret is OWNER-only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDetailsResponse(
    String sessionId,
    String protocolo,
    String estado,
    long duracionMs,
    EvaluationResponse evaluacion,
    String pairingCode
) {}
