package com.handwash.preview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Java has the last word on progress: it replaces the PC's own count in the overlay and tells the PC what it is. */
final class OverlayAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OverlayAuthority() {}

    static String withProgress(String overlayJson, SessionProgress progress) {
        try {
            JsonNode node = JSON.readTree(overlayJson);
            if (!(node instanceof ObjectNode object)) return overlayJson;
            object.put("cur", progress.current());
            object.put("exp", progress.expected());
            return JSON.writeValueAsString(object);
        } catch (RuntimeException notJson) {
            return overlayJson;
        }
    }

    static String hint(SessionProgress progress) {
        return "{\"hint\":{\"cur\":" + progress.current() + ",\"exp\":" + progress.expected() + "}}";
    }
}
