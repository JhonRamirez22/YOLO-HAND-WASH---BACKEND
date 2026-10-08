package com.handwash.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned envelope for diagnostic model output. Model-specific fields stay
 * flat for wire compatibility; backend-owned metadata always takes precedence.
 */
public final class InferenceResponse {
    private final Map<String, Object> properties;

    private InferenceResponse(Map<String, Object> properties) {
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static InferenceResponse from(Map<String, ?> modelFields, String inferenceSource,
                                         String sessionId, EvaluationResponse sessionState) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (modelFields != null) modelFields.forEach(properties::put);

        // These values describe the backend's decision, not untrusted model output.
        properties.put("inferenceSource", inferenceSource);
        if (sessionId != null) properties.put("sessionId", sessionId);
        else properties.remove("sessionId");
        properties.remove("estadoSesion");
        if (sessionState != null) properties.put("estadoSesion", sessionState);
        return new InferenceResponse(properties);
    }

    @JsonAnyGetter
    public Map<String, Object> jsonProperties() {
        return properties;
    }

    /** Read-only flattened view for internal callers and tests. */
    public Map<String, Object> asMap() {
        return properties;
    }
}
