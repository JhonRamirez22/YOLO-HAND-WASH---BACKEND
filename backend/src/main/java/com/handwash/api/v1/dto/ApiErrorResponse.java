package com.handwash.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Stable v1 error envelope; optional fields preserve each endpoint's established JSON shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
    String error,
    String mensaje,
    String producerProtocolVersion,
    EvaluationResponse estado
) {
    public static ApiErrorResponse of(String error) {
        return new ApiErrorResponse(error, null, null, null);
    }

    public static ApiErrorResponse withMessage(String error, String message) {
        return new ApiErrorResponse(error, message, null, null);
    }

    public static ApiErrorResponse producerVersionRequired() {
        return new ApiErrorResponse(
            "CAPTURADOR_PROTOCOL_VERSION_REQUIRED", null, "2", null);
    }

    public static ApiErrorResponse withState(String error, EvaluationResponse state) {
        return new ApiErrorResponse(error, null, null, state);
    }
}
