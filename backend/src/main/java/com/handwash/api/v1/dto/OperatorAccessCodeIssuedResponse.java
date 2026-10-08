package com.handwash.api.v1.dto;

public record OperatorAccessCodeIssuedResponse(String codeId, String code, String stationId,
                                               String email, long createdAtEpochMs) {}
