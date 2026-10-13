package com.handwash.api.v1.dto;

public record StationAdminRecord(
    String stationId,
    String stationName,
    long createdAtEpochMs,
    String createdBy,
    Long revokedAtEpochMs,
    Long lastStartedAtEpochMs,
    String activeSessionId,
    String producerClientId,
    Long activatedAtEpochMs
) {}
