package com.handwash.api.v1.dto;

/** Producer-declared artifacts; cloud validates these against the server's reviewed release. */
public record ProducerBuildIdentityRequest(
    String modelVersion,
    String modelSha256,
    String datasetVersion
) {}
