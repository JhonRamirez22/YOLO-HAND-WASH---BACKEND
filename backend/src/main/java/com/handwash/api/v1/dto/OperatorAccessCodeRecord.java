package com.handwash.api.v1.dto;

public record OperatorAccessCodeRecord(String codeId, String stationId, String email,
                                       String createdBy, long createdAtEpochMs,
                                       Long consumedAtEpochMs, String consumedBy,
                                       Long revokedAtEpochMs) {}
