package com.handwash.service;

import com.handwash.model.EvidenciaPoseManos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCvHandMotionEstimatorTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void staticPoseAndCommonCameraTransformDoNotCountAsRubbing() {
        var estimator = new OpenCvHandMotionEstimator();
        var initial = pose(0.0, 0.0, 1.0, 0.0, false);
        assertFalse(estimator.observe("session", "epoch", 1, SECOND, initial).valid());

        var still = estimator.observe("session", "epoch", 2, SECOND + 200_000_000L, initial);
        assertTrue(still.valid());
        assertEquals(2, still.visibleHands());
        assertTrue(still.movementNormalized() < 1e-7);

        var newEpoch = estimator.observe("session", "replacement", 1,
            SECOND + 400_000_000L, initial);
        assertFalse(newEpoch.valid());
        var transformed = estimator.observe("session", "replacement", 2,
            SECOND + 600_000_000L, pose(20.0, 12.0, 0.9, 0.12, false));
        assertTrue(transformed.valid());
        assertTrue(transformed.movementNormalized() < 1e-5);
    }

    @Test
    void relativeHandMovementSurvivesGlobalMotionCompensationAndDetectionOrderSwap() {
        var estimator = new OpenCvHandMotionEstimator();
        var initial = pose(0.0, 0.0, 1.0, 0.0, false);
        estimator.observe("session", "epoch", 1, SECOND, initial);

        var rubbing = pose(0.0, 0.0, 1.0, 0.0, false, 16.0);
        var measured = estimator.observe("session", "epoch", 2,
            SECOND + 200_000_000L, rubbing);
        assertTrue(measured.valid());
        assertTrue(measured.movementNormalized() > 0.06);

        estimator.resetSession("session");
        estimator.observe("session", "epoch", 3, SECOND + 400_000_000L, initial);
        var swapped = pose(0.0, 0.0, 1.0, 0.0, true);
        var orderChanged = estimator.observe("session", "epoch", 4,
            SECOND + 600_000_000L, swapped);
        assertTrue(orderChanged.valid());
        assertTrue(orderChanged.movementNormalized() < 1e-7);
    }

    @Test
    void missingOrMalformedPoseResetsTheTemporalBaseline() {
        var estimator = new OpenCvHandMotionEstimator();
        var initial = pose(0.0, 0.0, 1.0, 0.0, false);
        estimator.observe("session", "epoch", 1, SECOND, initial);

        var missing = estimator.observe("session", "epoch", 2,
            SECOND + 200_000_000L, null);
        assertFalse(missing.valid());
        assertEquals(0, missing.visibleHands());
        assertFalse(estimator.observe("session", "epoch", 3,
            SECOND + 400_000_000L, initial).valid());
        assertTrue(estimator.observe("session", "epoch", 4,
            SECOND + 600_000_000L, initial).valid());

        var malformed = new EvidenciaPoseManos(new Double[1][21][3], new Double[2][4],
            WIDTH, HEIGHT);
        assertFalse(estimator.observe("session", "epoch", 5,
            SECOND + 800_000_000L, malformed).valid());
    }

    @Test
    void staleIntervalsAndSessionDeletionDoNotBridgeEvidence() {
        var estimator = new OpenCvHandMotionEstimator();
        var pose = pose(0.0, 0.0, 1.0, 0.0, false);
        estimator.observe("session", "epoch", 1, SECOND, pose);
        assertFalse(estimator.observe("session", "epoch", 2,
            SECOND + 651_000_000L, pose).valid());
        assertTrue(estimator.observe("session", "epoch", 3,
            SECOND + 851_000_000L, pose).valid());

        estimator.removeSession("session");
        assertFalse(estimator.observe("session", "epoch", 4,
            SECOND + 1_051_000_000L, pose).valid());
    }

    private static EvidenciaPoseManos pose(double translateX, double translateY,
                                           double scale, double angle, boolean swap,
                                           double firstHandShiftY) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        for (int hand = 0; hand < 2; hand++) {
            int targetHand = swap ? 1 - hand : hand;
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int point = 0; point < 21; point++) {
                double x = 100 + (point % 5) * 10 + hand * 65;
                double y = 100 + (point / 5) * 10;
                if (hand == 0) y += firstHandShiftY;
                double movedX = (x * cosine - y * sine) * scale + translateX;
                double movedY = (x * sine + y * cosine) * scale + translateY;
                points[targetHand][point] = new Double[] {movedX, movedY, 0.95};
                minX = Math.min(minX, movedX);
                minY = Math.min(minY, movedY);
                maxX = Math.max(maxX, movedX);
                maxY = Math.max(maxY, movedY);
            }
            boxes[targetHand] = new Double[] {
                Math.max(0.0, minX - 4.0), Math.max(0.0, minY - 4.0),
                Math.min(WIDTH, maxX + 4.0), Math.min(HEIGHT, maxY + 4.0)
            };
        }
        return new EvidenciaPoseManos(points, boxes, WIDTH, HEIGHT);
    }

    private static EvidenciaPoseManos pose(double translateX, double translateY,
                                           double scale, double angle, boolean swap) {
        return pose(translateX, translateY, scale, angle, swap, 0.0);
    }
}
