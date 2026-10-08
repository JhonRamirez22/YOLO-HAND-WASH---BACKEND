package com.handwash.service;

import com.handwash.model.EvidenciaPoseManos;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.stereotype.Component;

import static org.bytedeco.opencv.global.opencv_calib3d.RANSAC;
import static org.bytedeco.opencv.global.opencv_calib3d.estimateAffinePartial2D;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_64F;

/** Re-measures bilateral YOLO pose movement in Java; only short-lived landmarks are retained. */
@Component
public final class OpenCvHandMotionEstimator {
    private static final int MIN_KEYPOINTS_PER_HAND = 7;
    private static final double INTENTION_KEYPOINT_CONFIDENCE = 0.30;
    private static final double MIN_BOX_SIZE_PX = 10.0;
    private static final double MAX_HAND_GAP_DIAGONALS = 1.0;
    private static final long MIN_INTERVAL_NANOS = 40_000_000L;
    private static final long MAX_INTERVAL_NANOS = 650_000_000L;
    private static final double RANSAC_REPROJECTION_THRESHOLD_PX = 4.0;
    private static final long RANSAC_ITERATIONS = 200L;

    public record Estimate(int visibleHands, double movementNormalized, boolean valid) {}

    private record Sample(long sequence, long observedAtNanos, EvidenciaPoseManos pose) {}

    private static final class SessionState {
        private String epoch;
        private Sample previous;
    }

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public Estimate observe(String sessionId, String epoch, long sequence,
                            long observedAtNanos, EvidenciaPoseManos pose) {
        if (sessionId == null || sessionId.isBlank() || epoch == null || epoch.isBlank()) {
            return invalid(0);
        }
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        synchronized (state) {
            if (!epoch.equals(state.epoch)) {
                state.epoch = epoch;
                state.previous = null;
            }
            if (pose == null || !pose.isStructurallyValid()) {
                state.previous = null;
                return invalid(0);
            }
            int visibleHands = pose.visibleHands();
            if (visibleHands != 2 || !pose.hasBilateralMotionPoints()) {
                state.previous = null;
                return invalid(visibleHands);
            }

            Sample current = new Sample(sequence, observedAtNanos, pose);
            Sample previous = state.previous;
            if (previous == null) {
                state.previous = current;
                return invalid(visibleHands);
            }
            if (sequence <= previous.sequence() || observedAtNanos <= previous.observedAtNanos()) {
                state.previous = sequence > previous.sequence() ? current : null;
                return invalid(visibleHands);
            }
            long elapsedNanos = observedAtNanos - previous.observedAtNanos();
            if (elapsedNanos < MIN_INTERVAL_NANOS || elapsedNanos > MAX_INTERVAL_NANOS
                || !handsAreClose(pose)) {
                state.previous = current;
                return invalid(visibleHands);
            }

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double residual = Math.min(
                normalizedResidual(previous.pose(), pose, false),
                normalizedResidual(previous.pose(), pose, true));
            state.previous = current;
            if (!Double.isFinite(residual) || residual < 0.0) return invalid(visibleHands);
            return new Estimate(visibleHands,
                Math.min(1.0, Math.max(0.0, residual / elapsedSeconds)), true);
        }
    }

    public void resetSession(String sessionId) {
        if (sessionId == null) return;
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            synchronized (state) { state.previous = null; }
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    private static double normalizedResidual(EvidenciaPoseManos before,
                                             EvidenciaPoseManos after,
                                             boolean swapHands) {
        double[] sourceX = new double[42];
        double[] sourceY = new double[42];
        double[] targetX = new double[42];
        double[] targetY = new double[42];
        int[] pairedPerHand = new int[2];
        int pairCount = 0;
        for (int hand = 0; hand < 2; hand++) {
            int currentHand = swapHands ? 1 - hand : hand;
            for (int point = 0; point < 21; point++) {
                if (before.keypointConfidence(hand, point) >= INTENTION_KEYPOINT_CONFIDENCE
                    && after.keypointConfidence(currentHand, point) >= INTENTION_KEYPOINT_CONFIDENCE) {
                    sourceX[pairCount] = before.keypointX(hand, point);
                    sourceY[pairCount] = before.keypointY(hand, point);
                    targetX[pairCount] = after.keypointX(currentHand, point);
                    targetY[pairCount] = after.keypointY(currentHand, point);
                    pairCount++;
                    pairedPerHand[hand]++;
                }
            }
        }
        if (pairedPerHand[0] < MIN_KEYPOINTS_PER_HAND
            || pairedPerHand[1] < MIN_KEYPOINTS_PER_HAND) return Double.POSITIVE_INFINITY;

        double meanX = 0.0;
        double meanY = 0.0;
        for (int index = 0; index < pairCount; index++) {
            meanX += sourceX[index];
            meanY += sourceY[index];
        }
        meanX /= pairCount;
        meanY /= pairCount;
        double variance = 0.0;
        for (int index = 0; index < pairCount; index++) {
            variance += square(sourceX[index] - meanX) + square(sourceY[index] - meanY);
        }
        double sourceScale = Math.sqrt(variance / pairCount);
        if (!Double.isFinite(sourceScale) || sourceScale < MIN_BOX_SIZE_PX) {
            return Double.POSITIVE_INFINITY;
        }

        try (Mat source = new Mat(1, pairCount, CV_32FC2);
             Mat target = new Mat(1, pairCount, CV_32FC2);
             Mat inliers = new Mat()) {
            try (FloatIndexer sourceIndex = (FloatIndexer) source.createIndexer();
                 FloatIndexer targetIndex = (FloatIndexer) target.createIndexer()) {
                for (int index = 0; index < pairCount; index++) {
                    sourceIndex.put(0, index, 0, (float) sourceX[index]);
                    sourceIndex.put(0, index, 1, (float) sourceY[index]);
                    targetIndex.put(0, index, 0, (float) targetX[index]);
                    targetIndex.put(0, index, 1, (float) targetY[index]);
                }
            }
            Mat estimated = estimateAffinePartial2D(source, target, inliers, RANSAC,
                RANSAC_REPROJECTION_THRESHOLD_PX, RANSAC_ITERATIONS, 0.99, 10L);
            if (estimated == null) return Double.POSITIVE_INFINITY;
            try (estimated; Mat affine = new Mat()) {
                if (estimated.empty()) return Double.POSITIVE_INFINITY;
                estimated.convertTo(affine, CV_64F);
                try (DoubleIndexer values = (DoubleIndexer) affine.createIndexer()) {
                    double squaredError = 0.0;
                    for (int index = 0; index < pairCount; index++) {
                        double predictedX = values.get(0, 0) * sourceX[index]
                            + values.get(0, 1) * sourceY[index] + values.get(0, 2);
                        double predictedY = values.get(1, 0) * sourceX[index]
                            + values.get(1, 1) * sourceY[index] + values.get(1, 2);
                        squaredError += square(predictedX - targetX[index])
                            + square(predictedY - targetY[index]);
                    }
                    return Math.sqrt(squaredError / pairCount) / sourceScale;
                }
            }
        }
    }

    private static boolean handsAreClose(EvidenciaPoseManos pose) {
        double firstWidth = pose.boxCoordinate(0, 2) - pose.boxCoordinate(0, 0);
        double firstHeight = pose.boxCoordinate(0, 3) - pose.boxCoordinate(0, 1);
        double secondWidth = pose.boxCoordinate(1, 2) - pose.boxCoordinate(1, 0);
        double secondHeight = pose.boxCoordinate(1, 3) - pose.boxCoordinate(1, 1);
        double firstDiagonal = Math.hypot(firstWidth, firstHeight);
        double secondDiagonal = Math.hypot(secondWidth, secondHeight);
        if (firstDiagonal < MIN_BOX_SIZE_PX || secondDiagonal < MIN_BOX_SIZE_PX) return false;
        double firstX1 = pose.boxCoordinate(0, 0);
        double firstY1 = pose.boxCoordinate(0, 1);
        double firstX2 = pose.boxCoordinate(0, 2);
        double firstY2 = pose.boxCoordinate(0, 3);
        double secondX1 = pose.boxCoordinate(1, 0);
        double secondY1 = pose.boxCoordinate(1, 1);
        double secondX2 = pose.boxCoordinate(1, 2);
        double secondY2 = pose.boxCoordinate(1, 3);
        double horizontalGap = Math.max(0.0, Math.max(firstX1 - secondX2,
            secondX1 - firstX2));
        double verticalGap = Math.max(0.0, Math.max(firstY1 - secondY2,
            secondY1 - firstY2));
        double gap = Math.hypot(horizontalGap, verticalGap);
        return gap <= MAX_HAND_GAP_DIAGONALS * (firstDiagonal + secondDiagonal) / 2.0;
    }

    private static Estimate invalid(int visibleHands) {
        return new Estimate(visibleHands, 0.0, false);
    }

    private static double square(double value) { return value * value; }
}
