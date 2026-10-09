package com.handwash.api.v1.dto;

public record OperatorGrantRecord(String subject, String email, String stationId,
                                  String stationName, String grantedBy, long grantedAtEpochMs,
                                  Long revokedAtEpochMs) {}
