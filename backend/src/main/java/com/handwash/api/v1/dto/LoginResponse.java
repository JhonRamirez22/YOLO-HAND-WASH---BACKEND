package com.handwash.api.v1.dto;

public record LoginResponse(String sessionId, String protocolo, String role,
                            String accessToken, String tokenType,
                            String accessTokenExpiresAt,
                            int producerProtocolVersion) {}
