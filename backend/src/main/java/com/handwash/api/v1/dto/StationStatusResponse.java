package com.handwash.api.v1.dto;

public record StationStatusResponse(
    boolean registered,
    String stationId,
    String stationName,
    Long createdAtEpochMs,
    String activeSessionId,
    String activeProtocol,
    /** True only while photo capture is active, so the kiosk never claims a photo it will not take. */
    boolean photoEvidence
) {
    public static StationStatusResponse unregistered() {
        return new StationStatusResponse(false, null, null, null, null, null, false);
    }
}
