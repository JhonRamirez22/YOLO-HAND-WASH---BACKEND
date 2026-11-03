package com.handwash.desktop;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void missingDiagnosticsRemainExplicit() {
        assertTrue(DesktopDashboard.diagnosticText(mapper.missingNode()).contains("esperando"));
    }

    @Test void observationCountersAreNotReportedAsBackendResets() {
        var data = mapper.readTree("{\"poseSequence\":7,\"strictHands\":1,\"counts\":{\"STRICT_KEYPOINTS_MISSING\":3}}");
        String text = DesktopDashboard.diagnosticText(data);
        assertTrue(text.contains("Pose #7"));
        assertTrue(text.contains("estrictas 1"));
        assertTrue(text.contains("NO reinicios Java"));
        assertTrue(text.contains("STRICT_KEYPOINTS_MISSING"));
    }

    @Test void externalLabelsAreExplicitlyUnvalidatedAndStale() {
        var data = mapper.readTree("{\"atulShadow\":{\"status\":\"SHADOW_ONLY_UNMAPPED\",\"fresh\":false,\"sequence\":9,\"full\":[{\"label\":\"Step_1\",\"probability\":0.9}]}}");
        String text = DesktopDashboard.diagnosticText(data);
        assertTrue(text.contains("SOLO COMPARACIÓN, etiquetas sin validar"));
        assertTrue(text.contains("fresco false"));
        assertTrue(text.contains("Step_1"));
    }

    @Test void poseObservationRateIsSeparatedFromModelInferenceRate() {
        var data = mapper.readTree("{\"fps\":{\"posePrimary\":5.5,\"pose\":8.0}}");
        String text = DesktopDashboard.diagnosticText(data);
        assertTrue(text.contains("5.5 inferencias primarias/s"));
        assertTrue(text.contains("8.0 llamadas/s (incluye recuperación)"));
    }
}
