package com.handwash.api.v1.dto;

public record StationEnrollmentResponse(
    String stationId,
    String stationName,
    String credential,
    long createdAtEpochMs
) {}
