package com.handwash.api.v1.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiErrorResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omitsOptionalFieldsToPreserveExistingSingleErrorShape() {
        JsonNode json = mapper.valueToTree(ApiErrorResponse.of("ACCESO_NO_AUTORIZADO"));

        assertEquals(Set.of("error"), fields(json));
        assertEquals("ACCESO_NO_AUTORIZADO", json.get("error").asText());
    }

    @Test
    void versionGatePreservesItsAdditionalProtocolVersionField() {
        JsonNode json = mapper.valueToTree(ApiErrorResponse.producerVersionRequired());

        assertEquals(Set.of("error", "producerProtocolVersion"), fields(json));
        assertEquals("CAPTURADOR_PROTOCOL_VERSION_REQUIRED", json.get("error").asText());
        assertEquals("2", json.get("producerProtocolVersion").asText());
    }

    private static Set<String> fields(JsonNode json) {
        Set<String> fields = new HashSet<>();
        fields.addAll(json.propertyNames());
        return fields;
    }
}
