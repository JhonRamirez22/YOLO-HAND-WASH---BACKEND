package com.handwash.recognition;

import com.handwash.model.HandPoseEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryShadowRecognizerTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void extractorNormalizesDistancesAcrossImageScale() {
        var extractor = new HandPoseGeometryExtractor();
        var small = extractor.extract(pose(100.0, 1.0), null).orElseThrow();
        var large = extractor.extract(pose(100.0, 2.0), null).orElseThrow();

        assertEquals(small.handDistanceNormalized(), large.handDistanceNormalized(), 1e-9);
        assertEquals(0.95, small.keypointQuality(), 1e-9);
        assertTrue(small.extendedFingerRatio() > 0.0);
    }

    @Test
    void missingBilateralPoseAbstainsAndStepSevenNeverMapsToFrictionMovement() {
        var recognizer = new GeometryShadowRecognizer();
        var firstOnly = pose(100.0, 1.0);
        Double[][][] oneHand = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        for (int hand = 0; hand < 2; hand++) {
            for (int point = 0; point < 21; point++) {
                oneHand[hand][point] = new Double[] {firstOnly.keypointX(hand, point),
                    firstOnly.keypointY(hand, point), hand == 0
                        ? firstOnly.keypointConfidence(hand, point) : 0.0};
            }
            for (int coordinate = 0; coordinate < 4; coordinate++) {
                boxes[hand][coordinate] = firstOnly.boxCoordinate(hand, coordinate);
            }
        }
        var invalid = new HandPoseEvidence(oneHand, boxes, 640, 480);
        var result = recognizer.observe(new HandPoseObservation("s", "e", 1, SECOND,
            invalid, null, "PASO_7_CIRCULARES"));

        assertEquals("ABSTAIN", result.status());
        assertFalse(result.clinicalDecisionAllowed());
        assertEquals(null, StepRecognizer.Movement.fromProducerCandidate("PASO_7_CIRCULARES"));
    }

    @Test
    void staticHandsDoNotBecomeAConfirmedMovementAndScoresAreNotProbabilities() {
        var recognizer = new GeometryShadowRecognizer();
        GeometryShadowSnapshot result = null;
        for (int index = 0; index < 6; index++) {
            result = recognizer.observe(new HandPoseObservation("s", "epoch", index + 1,
                SECOND + index * 200_000_000L, pose(100.0, 1.0), 0.0,
                "PASO_1_PALMAS"));
        }

        assertNotNull(result);
        assertFalse(result.clinicalDecisionAllowed());
        assertTrue(result.alternatives().size() == 6);
        assertFalse("CANDIDATE".equals(result.status()),
            "A fixed pose must not be proposed as an active friction movement");
        assertTrue(result.alternatives().stream().allMatch(candidate ->
            candidate.evidenceScore() >= 0.0 && candidate.evidenceScore() <= 1.0));
    }

    @Test
    void duplicateAndOutOfOrderFramesClearTemporalEvidence() {
        var recognizer = new GeometryShadowRecognizer();
        recognizer.observe(new HandPoseObservation("s", "epoch", 2, SECOND,
            pose(100.0, 1.0), null, null));

        var duplicate = recognizer.observe(new HandPoseObservation("s", "epoch", 2,
            SECOND + 100_000_000L, pose(100.0, 1.0), null, null));
        var afterReset = recognizer.observe(new HandPoseObservation("s", "epoch", 3,
            SECOND + 200_000_000L, pose(100.0, 1.0), null, null));

        assertEquals("DUPLICATE_OR_OUT_OF_ORDER_FRAME", duplicate.reason());
        assertEquals("TEMPORAL_WINDOW_INSUFFICIENT", afterReset.reason());
        assertEquals(1, afterReset.validObservations());
    }

    private static HandPoseEvidence pose(double secondHandX, double scale) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        for (int hand = 0; hand < 2; hand++) {
            double baseX = (hand == 0 ? 120.0 : secondHandX) * scale;
            double[] x = {0, -18, -23, -28, -32, -10, -10, -11, -12, 0, 0, 0, 0,
                10, 11, 12, 13, 19, 22, 24, 26};
            double[] y = {70, 54, 45, 36, 29, 48, 34, 21, 8, 45, 30, 17, 4,
                48, 35, 23, 11, 51, 41, 32, 23};
            for (int point = 0; point < 21; point++) {
                points[hand][point] = new Double[] {baseX + x[point] * scale,
                    150.0 * scale + y[point] * scale, 0.95};
            }
            boxes[hand] = new Double[] {baseX - 45 * scale, 130 * scale,
                baseX + 45 * scale, 235 * scale};
        }
        return new HandPoseEvidence(points, boxes, 640, 480);
    }
}
