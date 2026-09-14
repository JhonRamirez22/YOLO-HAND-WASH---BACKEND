package com.handwash.model;

/** One unattended-operation window of a station; open while {@code endedAtEpochMs} is null. */
public record StationShift(String shiftId, String stationId, String protocolCode, String startedBy,
                           long startedAtEpochMs, long endsAtEpochMs, Long endedAtEpochMs,
                           ShiftEndReason endReason) {
    public boolean open() { return endedAtEpochMs == null; }

    public enum ShiftEndReason {
        /** The planned end time arrived. */
        EXPIRED,
        /** An operator ended the shift or stopped the session. */
        STOPPED,
        /** The station was revoked by ADMIN. */
        STATION_REVOKED
    }
}
