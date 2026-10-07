package com.handwash.api.v1.dto;

import java.util.List;

public record ApiKeyRecord(String keyId, String stationId, String stationName, String label, List<String> scopes,
                           String createdBy, long createdAtEpochMs, Long lastUsedAtEpochMs, String lastUsedIp,
                           Long revokedAtEpochMs) {}
