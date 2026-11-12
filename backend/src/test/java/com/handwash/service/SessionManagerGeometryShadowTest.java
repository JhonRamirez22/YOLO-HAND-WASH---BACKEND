package com.handwash.service;

import com.handwash.agent.DetectionReceiver;
import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandPoseEvidence;
import com.handwash.model.WashSession;
import com.handwash.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionManagerGeometryShadowTest {
    @Test
    void optInGeometryIsPublishedAsShadowWithoutAdvancingJavaState() {
        SessionManager manager = new SessionManager(new DetectionReceiver());
        ReflectionTestUtils.setField(manager, "geometryShadowEnabled", true);
        WashSession session = partialSession();
        String initialState = session.getEstadoActualResponse().getEstadoActual();
        DetectionEvent event = event("shadow-on");

        ReflectionTestUtils.invokeMethod(manager, "recalculateMovementEvidence", event, session);

        var response = session.getEstadoActualResponse();
        assertNotNull(response.getGeometryShadow());
        assertFalse(response.getGeometryShadow().clinicalDecisionAllowed());
        assertEquals(initialState, response.getEstadoActual());
        assertEquals(0, response.getProgreso().getPasosCompletados());
    }

    @Test
    void geometryIsOffByDefaultAndNoShadowIsPublished() {
        SessionManager manager = new SessionManager(new DetectionReceiver());
        WashSession session = partialSession();

        ReflectionTestUtils.invokeMethod(manager, "recalculateMovementEvidence",
            event("shadow-off"), session);

        assertNull(session.getEstadoActualResponse().getGeometryShadow());
        assertEquals(0, session.getEstadoActualResponse().getProgreso().getPasosCompletados());
    }

    private static WashSession partialSession() {
        WashSession session = new WashSession("session", ProtocolType.DOMESTICO);
        ReflectionTestUtils.setField(session, "modoEvaluacion", "FRICCION_PARCIAL");
        return session;
    }

    private static DetectionEvent event(String sessionId) {
        var event = new DetectionEvent(sessionId, "Paso1_Palmas", 0.9f,
            "2026-10-09T00:00:00Z");
        event.setProducerEpoch("epoch");
        event.setFrameSequence(1L);
        event.setEventType("DETECTION");
        event.setServerIngressAtMonotonicNanos(System.nanoTime());
        event.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 20L));
        event.setEvidenciaPoseManos(pose());
        return event;
    }

    private static HandPoseEvidence pose() {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        for (int hand = 0; hand < 2; hand++) {
            double baseX = 100.0 + hand * 180.0;
            double[] x = {0, -18, -23, -28, -32, -10, -10, -11, -12, 0, 0, 0, 0,
                10, 11, 12, 13, 19, 22, 24, 26};
            double[] y = {70, 54, 45, 36, 29, 48, 34, 21, 8, 45, 30, 17, 4,
                48, 35, 23, 11, 51, 41, 32, 23};
            for (int point = 0; point < 21; point++) {
                points[hand][point] = new Double[] {baseX + x[point], 150.0 + y[point], 0.95};
            }
            boxes[hand] = new Double[] {baseX - 45, 130.0, baseX + 45, 235.0};
        }
        return new HandPoseEvidence(points, boxes, 640, 480);
    }
}
