package com.handwash.api.v1.dto;

/** The key is returned once; afterwards only its hash exists. */
public record ApiKeyCreatedResponse(String keyId, String key, String stationId, String label, java.util.List<String> scopes) {}
