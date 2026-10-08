package com.handwash.model;

/** Short-lived numeric landmarks from one frame; never contains image pixels. */
public final class HandPoseEvidence {
    private static final int EXPECTED_HANDS = 2;
    private static final int KEYPOINTS_PER_HAND = 21;
    private static final int MIN_VISIBLE_KEYPOINTS = 7;
    private static final double POSE_CONFIDENCE = 0.10;
    private static final double DEDUP_CONFIDENCE = 0.10;

    private final double[] keypoints;
    private final double[] boxes;
    private final int frameWidth;
    private final int frameHeight;

    public HandPoseEvidence(Double[][][] keypoints, Double[][] boxes,
                              Integer frameWidth, Integer frameHeight) {
        this.frameWidth = frameWidth == null ? 0 : frameWidth;
        this.frameHeight = frameHeight == null ? 0 : frameHeight;
        if (isValidEvidence(keypoints, boxes, this.frameWidth, this.frameHeight)) {
            this.keypoints = flattenKeypoints(keypoints);
            this.boxes = flattenBoxes(boxes);
        } else {
            // Request-controlled dimensions are validated before cloning so a malformed
            // payload cannot multiply its memory cost through defensive copies.
            this.keypoints = null;
            this.boxes = null;
        }
    }

    public double keypointX(int hand, int point) {
        return keypoints[keypointIndex(hand, point, 0)];
    }
    public double keypointY(int hand, int point) {
        return keypoints[keypointIndex(hand, point, 1)];
    }
    public double keypointConfidence(int hand, int point) {
        return keypoints[keypointIndex(hand, point, 2)];
    }
    public double boxCoordinate(int hand, int coordinate) {
        if (boxes == null) throw new IllegalStateException("La evidencia de pose es inválida");
        if (hand < 0 || hand >= EXPECTED_HANDS || coordinate < 0 || coordinate >= 4) {
            throw new IndexOutOfBoundsException("Coordenada de caja fuera de rango");
        }
        return boxes[hand * 4 + coordinate];
    }
    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }

    public boolean isStructurallyValid() {
        return keypoints != null && boxes != null;
    }

    private static boolean isValidEvidence(Double[][][] keypoints, Double[][] boxes,
                                           int frameWidth, int frameHeight) {
        if (frameWidth < 1 || frameHeight < 1 || frameWidth > 8192 || frameHeight > 8192
            || (long) frameWidth * frameHeight > 33_554_432L
            || keypoints == null || keypoints.length != EXPECTED_HANDS
            || boxes == null || boxes.length != EXPECTED_HANDS) return false;
        for (int hand = 0; hand < EXPECTED_HANDS; hand++) {
            if (keypoints[hand] == null || keypoints[hand].length != KEYPOINTS_PER_HAND
                || boxes[hand] == null || boxes[hand].length != 4) return false;
            for (Double[] point : keypoints[hand]) {
                if (point == null || point.length != 3
                    || !inside(point[0], frameWidth) || !inside(point[1], frameHeight)
                    || !unit(point[2])) return false;
            }
            Double[] box = boxes[hand];
            if (!inside(box[0], frameWidth) || !inside(box[1], frameHeight)
                || !inside(box[2], frameWidth) || !inside(box[3], frameHeight)
                || box[2] <= box[0] || box[3] <= box[1]) return false;
        }
        return true;
    }

    public int visibleHands() {
        if (!isStructurallyValid()) return 0;
        int visible = 0;
        for (int hand = 0; hand < EXPECTED_HANDS; hand++) {
            int count = 0;
            for (int point = 0; point < KEYPOINTS_PER_HAND; point++) {
                if (keypoints[keypointIndexUnchecked(hand, point, 2)] >= POSE_CONFIDENCE) count++;
            }
            if (count >= MIN_VISIBLE_KEYPOINTS) visible++;
        }
        return visible == 2 && duplicateHands() ? 1 : visible;
    }

    public boolean hasBilateralMotionPoints() {
        if (visibleHands() != EXPECTED_HANDS) return false;
        for (int hand = 0; hand < EXPECTED_HANDS; hand++) {
            int count = 0;
            for (int point = 0; point < KEYPOINTS_PER_HAND; point++) {
                if (keypoints[keypointIndexUnchecked(hand, point, 2)] >= 0.30) count++;
            }
            if (count < MIN_VISIBLE_KEYPOINTS) return false;
        }
        return true;
    }

    private boolean duplicateHands() {
        double firstX1 = boxes[0];
        double firstY1 = boxes[1];
        double firstX2 = boxes[2];
        double firstY2 = boxes[3];
        double secondX1 = boxes[4];
        double secondY1 = boxes[5];
        double secondX2 = boxes[6];
        double secondY2 = boxes[7];
        double firstArea = (firstX2 - firstX1) * (firstY2 - firstY1);
        double secondArea = (secondX2 - secondX1) * (secondY2 - secondY1);
        double intersection = Math.max(0.0, Math.min(firstX2, secondX2)
            - Math.max(firstX1, secondX1)) * Math.max(0.0,
            Math.min(firstY2, secondY2) - Math.max(firstY1, secondY1));
        double union = firstArea + secondArea - intersection;
        double overlap = union > 0.0 ? intersection / union : 0.0;
        double firstDiagonal = Math.hypot(firstX2 - firstX1, firstY2 - firstY1);
        double secondDiagonal = Math.hypot(secondX2 - secondX1, secondY2 - secondY1);
        double meanDiagonal = (firstDiagonal + secondDiagonal) / 2.0;
        if (overlap < 0.45 || meanDiagonal <= 0.0) return false;
        double centerDistance = Math.hypot(
            (firstX1 + firstX2 - secondX1 - secondX2) / 2.0,
            (firstY1 + firstY2 - secondY1 - secondY2) / 2.0);
        if (centerDistance > 0.12 * meanDiagonal) return false;

        double[] shifts = new double[KEYPOINTS_PER_HAND];
        int shared = 0;
        for (int index = 0; index < KEYPOINTS_PER_HAND; index++) {
            int firstOffset = keypointIndexUnchecked(0, index, 0);
            int secondOffset = keypointIndexUnchecked(1, index, 0);
            if (keypoints[firstOffset + 2] >= DEDUP_CONFIDENCE
                && keypoints[secondOffset + 2] >= DEDUP_CONFIDENCE) {
                shifts[shared++] = Math.hypot(
                    keypoints[firstOffset] - keypoints[secondOffset],
                    keypoints[firstOffset + 1] - keypoints[secondOffset + 1]);
            }
        }
        if (shared < MIN_VISIBLE_KEYPOINTS) return false;
        java.util.Arrays.sort(shifts, 0, shared);
        double median = shared % 2 == 0
            ? (shifts[shared / 2 - 1] + shifts[shared / 2]) / 2.0
            : shifts[shared / 2];
        return median <= 0.075 * meanDiagonal;
    }

    private static boolean inside(Double value, int maximum) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= maximum;
    }

    private static boolean unit(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static double[] flattenKeypoints(Double[][][] source) {
        double[] result = new double[EXPECTED_HANDS * KEYPOINTS_PER_HAND * 3];
        int offset = 0;
        for (int hand = 0; hand < EXPECTED_HANDS; hand++) {
            for (int point = 0; point < KEYPOINTS_PER_HAND; point++) {
                for (int axis = 0; axis < 3; axis++) result[offset++] = source[hand][point][axis];
            }
        }
        return result;
    }

    private static double[] flattenBoxes(Double[][] source) {
        double[] result = new double[EXPECTED_HANDS * 4];
        int offset = 0;
        for (int hand = 0; hand < EXPECTED_HANDS; hand++) {
            for (int coordinate = 0; coordinate < 4; coordinate++) {
                result[offset++] = source[hand][coordinate];
            }
        }
        return result;
    }

    private int keypointIndex(int hand, int point, int axis) {
        if (keypoints == null) throw new IllegalStateException("La evidencia de pose es inválida");
        if (hand < 0 || hand >= EXPECTED_HANDS || point < 0 || point >= KEYPOINTS_PER_HAND
            || axis < 0 || axis >= 3) {
            throw new IndexOutOfBoundsException("Landmark fuera de rango");
        }
        return keypointIndexUnchecked(hand, point, axis);
    }

    private static int keypointIndexUnchecked(int hand, int point, int axis) {
        return (hand * KEYPOINTS_PER_HAND + point) * 3 + axis;
    }
}
