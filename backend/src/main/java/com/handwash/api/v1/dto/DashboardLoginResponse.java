package com.handwash.api.v1.dto;

/** Read-only dashboard capability returned after pairing with an active station session. */
public record DashboardLoginResponse(String sessionId, String protocolo, String role,
                                     String accessToken, String tokenType,
                                     String accessTokenExpiresAt) {}
