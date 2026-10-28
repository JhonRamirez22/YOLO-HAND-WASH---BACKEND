package com.handwash.api.v1.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InferenceResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flattensModelFieldsToPreserveExistingJsonContract() throws Exception {
        Map<String, Object> modelOutput = new LinkedHashMap<>();
        modelOutput.put("model", "YOLO26");
        modelOutput.put("best", null);

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(
            InferenceResponse.from(modelOutput, "ONNX_LOCAL", null, null)));

        assertEquals("YOLO26", json.get("model").asText());
        assertTrue(json.has("best"));
        assertTrue(json.get("best").isNull());
        assertEquals("ONNX_LOCAL", json.get("inferenceSource").asText());
        assertFalse(json.has("properties"));
    }

    @Test
    void backendMetadataOverridesUntrustedModelFields() {
        Map<String, Object> modelOutput = new LinkedHashMap<>();
        modelOutput.put("inferenceSource", "SPOOFED");
        modelOutput.put("sessionId", "other-session");
        modelOutput.put("estadoSesion", Map.of("estadoActual", "COMPLETO"));

        Map<String, Object> response = InferenceResponse.from(
            modelOutput, "ONNX_LOCAL", "authorized-session", null).asMap();

        assertEquals("ONNX_LOCAL", response.get("inferenceSource"));
        assertEquals("authorized-session", response.get("sessionId"));
        assertFalse(response.containsKey("estadoSesion"));
    }
}
