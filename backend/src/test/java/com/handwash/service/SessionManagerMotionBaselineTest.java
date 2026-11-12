package com.handwash.service;

import com.handwash.agent.DetectionReceiver;
import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandPoseEvidence;
import com.handwash.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SessionManagerMotionBaselineTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    @Test
    void firstValidPoseSeedsBaselineAndNextStepCandidateCanBeMeasured() throws InterruptedException {
        SessionManager manager = new SessionManager(new DetectionReceiver());
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 0L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String token = manager.getOwnerToken(sessionId);
        var epoch = manager.registrarProducerEpoch(sessionId, token).registration().producerEpoch();

        try {
            var first = post(manager, sessionId, token, epoch, 1L, 0.0);
            assertEquals(SessionManager.DetectionOutcome.FILTERED, first.outcome(),
                "The first bilateral sample initializes OpenCV's temporal baseline");
            assertEquals("OpenCV no pudo medir movimiento bilateral reciente", first.detail());

            Thread.sleep(60L);
            var second = post(manager, sessionId, token, epoch, 2L, 16.0);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED, second.outcome(),
                second.detail());
            assertNotNull(second.event(), "The second sample must carry a measured motion event");
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    private static SessionManager.DetectionResult post(SessionManager manager, String sessionId,
                                                        String token, String epoch,
                                                        long sequence, double firstHandShiftY) {
        DetectionEvent event = new DetectionEvent(
            sessionId, "PASO_1_PALMAS", 0.95f, Instant.now().toString());
        event.setProducerEpoch(epoch);
        event.setEventType("DETECTION");
        event.setFrameSequence(sequence);
        event.setCaptureAgeMs(0L);
        event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, 0.2, true, 0L));
        event.setEvidenciaPoseManos(pose(firstHandShiftY));
        event.setServerIngressAtMonotonicNanos(System.nanoTime());
        return manager.procesarDeteccionHttp(event, token);
    }

    private static HandPoseEvidence pose(double firstHandShiftY) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        for (int hand = 0; hand < 2; hand++) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int point = 0; point < 21; point++) {
                double x = 100.0 + (point % 5) * 10.0 + hand * 65.0;
                double y = 100.0 + (point / 5) * 10.0
                    + (hand == 0 ? firstHandShiftY : 0.0);
                points[hand][point] = new Double[] {x, y, 0.95};
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            boxes[hand] = new Double[] {minX - 4.0, minY - 4.0, maxX + 4.0, maxY + 4.0};
        }
        return new HandPoseEvidence(points, boxes, WIDTH, HEIGHT);
    }
}
