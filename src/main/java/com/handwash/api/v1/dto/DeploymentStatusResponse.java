package com.handwash.api.v1.dto;

/** Public, non-sensitive deployment mode for a visible dashboard safety notice. */
public record DeploymentStatusResponse(String mode, boolean clinicalDecisionAllowed, String notice) {}
