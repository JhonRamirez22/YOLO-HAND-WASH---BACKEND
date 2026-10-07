package com.handwash.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Stable v1 transport acknowledgement; dashboard state is optional by contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionAckResponse(boolean accepted, boolean filtered, EvaluationResponse estado) {}
