package com.handwash.api.v1.dto;

public record ProducerBootstrapResponse(String sessionId, String sessionToken,
                                        String sessionTokenExpiresAt, String producerEpoch,
                                        int protocolVersion) {}
