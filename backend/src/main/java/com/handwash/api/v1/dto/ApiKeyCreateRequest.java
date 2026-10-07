package com.handwash.api.v1.dto;

import java.util.List;

public record ApiKeyCreateRequest(String stationId, String label, List<String> scopes) {}
