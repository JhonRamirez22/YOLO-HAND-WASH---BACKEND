package com.handwash.api.v1.dto;

public record DevicePairResponse(String sessionId, String protocolo, String accessToken,
                                 String accessTokenExpiresAt, int producerProtocolVersion) {}
