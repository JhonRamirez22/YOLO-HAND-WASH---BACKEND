package com.handwash.api.v1.dto;

/** Versioned discovery response for the single active local session. */
public record ActiveSessionResponse(
    String sessionId,
    String protocolo,
    String estado,
    boolean accessRequired
) {}
