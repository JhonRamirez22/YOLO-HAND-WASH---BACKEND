package com.handwash.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HandPoseEvidenceTest {
    @Test
    void rejectsUnexpectedArrayShapesBeforeRetainingOrCopyingThem() {
        Double[][][] oversizedHands = new Double[100_000][][];
        Double[][] boxes = validBoxes();

        var evidence = new HandPoseEvidence(oversizedHands, boxes, 640, 480);

        assertFalse(evidence.isStructurallyValid());
        assertThrows(IllegalStateException.class, () -> evidence.keypointX(0, 0));
        assertThrows(IllegalStateException.class, () -> evidence.boxCoordinate(0, 0));
    }

    @Test
    void rejectsInvalidPointValuesWithoutKeepingPartialPose() {
        Double[][][] points = validPoints();
        points[1][20][0] = Double.NaN;

        var evidence = new HandPoseEvidence(points, validBoxes(), 640, 480);

        assertFalse(evidence.isStructurallyValid());
        assertThrows(IllegalStateException.class, () -> evidence.keypointX(1, 20));
    }

    @Test
    void validPoseIsDefensivelyCopiedOnInputAndOutput() {
        Double[][][] points = validPoints();
        Double[][] boxes = validBoxes();
        var evidence = new HandPoseEvidence(points, boxes, 640, 480);
        points[0][0][0] = 300.0;
        boxes[0][0] = 300.0;

        assertTrue(evidence.isStructurallyValid());
        assertEquals(100.0, evidence.keypointX(0, 0), 0.0);
        assertEquals(90.0, evidence.boxCoordinate(0, 0), 0.0);
    }

    @Test
    void overlappingButDistinctHandsRemainBilateral() {
        Double[][][] points = new Double[2][21][3];
        for (int hand = 0; hand < 2; hand++) {
            double offset = hand == 0 ? 0.0 : 25.0;
            for (int point = 0; point < 21; point++) {
                points[hand][point] = new Double[] {
                    120.0 + offset + point % 5 * 2.0,
                    70.0 + point / 5 * 2.0,
                    0.9
                };
            }
        }
        Double[][] overlappingBoxes = {
            {100.0, 50.0, 220.0, 180.0},
            {110.0, 50.0, 230.0, 180.0}
        };

        var evidence = new HandPoseEvidence(points, overlappingBoxes, 640, 480);

        assertTrue(evidence.isStructurallyValid());
        assertEquals(2, evidence.visibleHands());
        assertTrue(evidence.hasBilateralMotionPoints());
    }

    @Test
    void duplicateOverlappingHandPredictionsDoNotCountAsTwoHands() {
        Double[][][] points = new Double[2][21][3];
        for (int hand = 0; hand < 2; hand++) {
            for (int point = 0; point < 21; point++) {
                points[hand][point] = new Double[] {
                    120.0 + point % 5 * 2.0,
                    70.0 + point / 5 * 2.0,
                    0.9
                };
            }
        }
        Double[][] duplicateBoxes = {
            {100.0, 50.0, 220.0, 180.0},
            {100.0, 50.0, 220.0, 180.0}
        };

        var evidence = new HandPoseEvidence(points, duplicateBoxes, 640, 480);

        assertTrue(evidence.isStructurallyValid());
        assertEquals(1, evidence.visibleHands());
        assertFalse(evidence.hasBilateralMotionPoints());
    }

    private static Double[][][] validPoints() {
        Double[][][] points = new Double[2][21][3];
        for (int hand = 0; hand < 2; hand++) {
            for (int point = 0; point < 21; point++) {
                points[hand][point] = new Double[] {
                    100.0 + hand * 60.0 + point % 5,
                    100.0 + point / 5,
                    0.9
                };
            }
        }
        return points;
    }

    private static Double[][] validBoxes() {
        return new Double[][] {
            {90.0, 90.0, 140.0, 140.0},
            {150.0, 90.0, 200.0, 140.0}
        };
    }
}
