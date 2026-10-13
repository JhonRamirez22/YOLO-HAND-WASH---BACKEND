package com.handwash.api.v1.dto;

import java.util.List;

public record StationProvisionRequest(String stationName, String producerClientId, List<String> operatorEmails) {}
