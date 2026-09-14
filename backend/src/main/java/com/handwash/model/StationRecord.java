package com.handwash.model;

public record StationRecord(
    String stationId,
    String stationName,
    long createdAtEpochMs,
    String createdBy,
    Long revokedAtEpochMs,
    Long lastStartedAtEpochMs,
    String activeSessionId,
    String activeRequestId,
    String producerClientId
) {
    public StationRecord(String stationId, String stationName, long createdAtEpochMs,
                         String createdBy, Long revokedAtEpochMs, Long lastStartedAtEpochMs,
                         String activeSessionId, String activeRequestId) {
        this(stationId, stationName, createdAtEpochMs, createdBy, revokedAtEpochMs,
            lastStartedAtEpochMs, activeSessionId, activeRequestId, null);
    }
}
