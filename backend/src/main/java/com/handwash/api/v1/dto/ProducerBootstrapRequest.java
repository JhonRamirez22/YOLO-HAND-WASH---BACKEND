package com.handwash.api.v1.dto;

public record ProducerBootstrapRequest(String stationId, String modelVersion,
                                       String modelSha256, String datasetVersion) {}
