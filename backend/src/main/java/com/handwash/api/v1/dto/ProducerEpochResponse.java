package com.handwash.api.v1.dto;

public record ProducerEpochResponse(String sessionId, String producerEpoch,
                                    int protocolVersion, boolean rotated) {}
