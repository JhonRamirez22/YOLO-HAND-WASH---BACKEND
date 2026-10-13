package com.handwash.api.v1.dto;

import java.util.List;

/** The activation token and the temporary passwords are returned once and never stored in clear. */
public record StationProvisionResponse(
    String stationId,
    String stationName,
    String producerClientId,
    String activationToken,
    long activationExpiresAtEpochMs,
    List<OperatorAccount> operators,
    List<OperatorFailure> operatorFailures
) {
    public record OperatorAccount(String subject, String email, String temporaryPassword) {}
    public record OperatorFailure(String email, String code) {}
}
