package com.handwash.model;

public record EncounterAuditRecord(
    String sessionId,
    String protocolCode,
    String status,
    long startedAtEpochMs,
    Long endedAtEpochMs,
    String modelVersion,
    String modelSha256,
    String datasetVersion,
    int attemptsRestarted,
    String producerClientId,
    String producerEpoch,
    Integer protocolProfileVersion
) {
    public EncounterAuditRecord(String sessionId, String protocolCode, String status,
                                long startedAtEpochMs, Long endedAtEpochMs, String modelVersion,
                                String modelSha256, String datasetVersion, int attemptsRestarted) {
        this(sessionId, protocolCode, status, startedAtEpochMs, endedAtEpochMs, modelVersion,
            modelSha256, datasetVersion, attemptsRestarted, null, null, null);
    }

    public EncounterAuditRecord(String sessionId, String protocolCode, String status,
                                long startedAtEpochMs, Long endedAtEpochMs, String modelVersion,
                                String modelSha256, String datasetVersion, int attemptsRestarted,
                                String producerClientId, String producerEpoch) {
        this(sessionId, protocolCode, status, startedAtEpochMs, endedAtEpochMs, modelVersion,
            modelSha256, datasetVersion, attemptsRestarted, producerClientId, producerEpoch, null);
    }
}
