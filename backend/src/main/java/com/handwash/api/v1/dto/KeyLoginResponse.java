package com.handwash.api.v1.dto;

/** stationCredential is present only when this call linked the Mac; macAlreadyLinked means another Mac holds it. */
public record KeyLoginResponse(String sessionToken, long expiresAtEpochMs, String stationId, String stationName,
                               String label, String stationCredential, boolean macAlreadyLinked) {}
