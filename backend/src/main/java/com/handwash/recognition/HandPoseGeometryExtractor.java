package com.handwash.recognition;

import com.handwash.model.HandPoseEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Converts 2-D YOLO hand landmarks into scale-normalized numeric features. */
public final class HandPoseGeometryExtractor {
    private static final double MIN_CONFIDENCE = 0.10;
    private static final int[] PALM_BASE = {5, 9, 13, 17};
    private static final int[][] FINGERS = {{5, 6, 7, 8}, {9, 10, 11, 12},
        {13, 14, 15, 16}, {17, 18, 19, 20}};

    public record Features(double handDistanceNormalized, double boxOverlap,
                           double extendedFingerRatio, double flexionRatio,
                           Double thumbToOppositePalmNormalized,
                           Double fingertipsToOppositePalmNormalized,
                           int crossHandFingerIntersections,
                           double relativeX, double relativeY,
                           Double thumbAngleFirst, Double thumbAngleSecond,
                           double keypointQuality, Double openCvResidualNormalized) {}

    private record Point(double x, double y) {}
    private record Hand(Point center, double palmScale, double boxDiagonal,
                        double extendedRatio, double flexionRatio,
                        Double thumbAngle, Point thumbTip, List<Point> fingertips,
                        int visibleKeypoints, double meanConfidence) {}
    private record Segment(Point start, Point end) {}

    public Optional<Features> extract(HandPoseEvidence pose, Double openCvResidualNormalized) {
        if (pose == null || !pose.isStructurallyValid() || pose.visibleHands() != 2) {
            return Optional.empty();
        }
        Hand first = hand(pose, 0);
        Hand second = hand(pose, 1);
        if (first == null || second == null) return Optional.empty();
        if (first.center().x() > second.center().x()) {
            Hand swap = first;
            first = second;
            second = swap;
        }

        double scale = (first.palmScale() + second.palmScale()) / 2.0;
        if (!Double.isFinite(scale) || scale < 2.0) return Optional.empty();
        double dx = second.center().x() - first.center().x();
        double dy = second.center().y() - first.center().y();
        Double thumbDistance = meanOrNull(
            distanceOrNull(first.thumbTip(), second.center()),
            distanceOrNull(second.thumbTip(), first.center()));
        Double fingertipDistance = mean(fingertipDistances(first, second.center()),
            fingertipDistances(second, first.center()));
        double quality = Math.min(first.visibleKeypoints(), second.visibleKeypoints()) / 21.0
            * (first.meanConfidence() + second.meanConfidence()) / 2.0;
        Double residual = finiteNonNegative(openCvResidualNormalized)
            ? openCvResidualNormalized : null;

        return Optional.of(new Features(
            Math.hypot(dx, dy) / scale,
            boxOverlap(pose),
            (first.extendedRatio() + second.extendedRatio()) / 2.0,
            (first.flexionRatio() + second.flexionRatio()) / 2.0,
            thumbDistance == null ? null : thumbDistance / scale,
            fingertipDistance == null ? null : fingertipDistance / scale,
            crossHandIntersections(pose), dx / scale, dy / scale,
            first.thumbAngle(), second.thumbAngle(), quality, residual));
    }

    private static Hand hand(HandPoseEvidence pose, int hand) {
        int visible = 0;
        double confidenceSum = 0.0;
        for (int index = 0; index < 21; index++) {
            double confidence = pose.keypointConfidence(hand, index);
            if (confidence >= MIN_CONFIDENCE) {
                visible++;
                confidenceSum += confidence;
            }
        }
        if (visible < 7 || pose.keypointConfidence(hand, 0) < MIN_CONFIDENCE) return null;
        Point wrist = point(pose, hand, 0);
        List<Point> palmBases = new ArrayList<>(4);
        for (int index : PALM_BASE) {
            if (pose.keypointConfidence(hand, index) >= MIN_CONFIDENCE) {
                palmBases.add(point(pose, hand, index));
            }
        }
        if (palmBases.size() < 3) return null;
        Point center = average(palmBases);
        double palmScale = distance(wrist, center);
        double boxWidth = pose.boxCoordinate(hand, 2) - pose.boxCoordinate(hand, 0);
        double boxHeight = pose.boxCoordinate(hand, 3) - pose.boxCoordinate(hand, 1);
        double boxDiagonal = Math.hypot(boxWidth, boxHeight);
        if (palmScale < 2.0 || boxDiagonal < 10.0) return null;

        double extendedSum = 0.0;
        double flexionSum = 0.0;
        int extendedCount = 0;
        int flexionCount = 0;
        List<Point> tips = new ArrayList<>(4);
        for (int[] finger : FINGERS) {
            int mcp = finger[0], pip = finger[1], dip = finger[2], tip = finger[3];
            if (pose.keypointConfidence(hand, mcp) >= MIN_CONFIDENCE
                && pose.keypointConfidence(hand, tip) >= MIN_CONFIDENCE) {
                extendedSum += distance(point(pose, hand, mcp), point(pose, hand, tip)) / palmScale;
                extendedCount++;
                tips.add(point(pose, hand, tip));
            }
            if (pose.keypointConfidence(hand, mcp) >= MIN_CONFIDENCE
                && pose.keypointConfidence(hand, pip) >= MIN_CONFIDENCE
                && pose.keypointConfidence(hand, dip) >= MIN_CONFIDENCE) {
                flexionSum += flexion(point(pose, hand, mcp), point(pose, hand, pip),
                    point(pose, hand, dip));
                flexionCount++;
            }
        }
        if (extendedCount < 2 || flexionCount < 2) return null;

        Point thumbTip = pose.keypointConfidence(hand, 4) >= MIN_CONFIDENCE
            ? point(pose, hand, 4) : null;
        Double thumbAngle = thumbTip == null ? null
            : Math.atan2(thumbTip.y() - center.y(), thumbTip.x() - center.x());
        return new Hand(center, palmScale, boxDiagonal, extendedSum / extendedCount,
            flexionSum / flexionCount, thumbAngle, thumbTip, tips, visible,
            confidenceSum / visible);
    }

    private static double flexion(Point mcp, Point pip, Point dip) {
        double ax = mcp.x() - pip.x(), ay = mcp.y() - pip.y();
        double bx = dip.x() - pip.x(), by = dip.y() - pip.y();
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator < 1e-9) return 0.0;
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
        double angle = Math.toDegrees(Math.acos(cosine));
        return clamp((165.0 - angle) / 100.0);
    }

    private static int crossHandIntersections(HandPoseEvidence pose) {
        List<Segment> first = fingerSegments(pose, 0);
        List<Segment> second = fingerSegments(pose, 1);
        int intersections = 0;
        for (Segment a : first) {
            for (Segment b : second) {
                if (intersects(a, b)) intersections++;
            }
        }
        return intersections;
    }

    private static List<Segment> fingerSegments(HandPoseEvidence pose, int hand) {
        List<Segment> segments = new ArrayList<>(12);
        for (int[] finger : FINGERS) {
            for (int index = 0; index < finger.length - 1; index++) {
                int from = finger[index], to = finger[index + 1];
                if (pose.keypointConfidence(hand, from) >= MIN_CONFIDENCE
                    && pose.keypointConfidence(hand, to) >= MIN_CONFIDENCE) {
                    segments.add(new Segment(point(pose, hand, from), point(pose, hand, to)));
                }
            }
        }
        return segments;
    }

    private static boolean intersects(Segment first, Segment second) {
        double o1 = orientation(first.start(), first.end(), second.start());
        double o2 = orientation(first.start(), first.end(), second.end());
        double o3 = orientation(second.start(), second.end(), first.start());
        double o4 = orientation(second.start(), second.end(), first.end());
        return o1 * o2 < -1e-6 && o3 * o4 < -1e-6;
    }

    private static double orientation(Point a, Point b, Point c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static double boxOverlap(HandPoseEvidence pose) {
        double ax1 = pose.boxCoordinate(0, 0), ay1 = pose.boxCoordinate(0, 1);
        double ax2 = pose.boxCoordinate(0, 2), ay2 = pose.boxCoordinate(0, 3);
        double bx1 = pose.boxCoordinate(1, 0), by1 = pose.boxCoordinate(1, 1);
        double bx2 = pose.boxCoordinate(1, 2), by2 = pose.boxCoordinate(1, 3);
        double intersection = Math.max(0, Math.min(ax2, bx2) - Math.max(ax1, bx1))
            * Math.max(0, Math.min(ay2, by2) - Math.max(ay1, by1));
        double union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    private static List<Double> fingertipDistances(Hand from, Point to) {
        List<Double> distances = new ArrayList<>(from.fingertips().size());
        for (Point tip : from.fingertips()) distances.add(distance(tip, to));
        return distances;
    }

    private static Point point(HandPoseEvidence pose, int hand, int index) {
        return new Point(pose.keypointX(hand, index), pose.keypointY(hand, index));
    }

    private static Point average(List<Point> points) {
        double x = 0.0, y = 0.0;
        for (Point point : points) { x += point.x(); y += point.y(); }
        return new Point(x / points.size(), y / points.size());
    }

    private static Double mean(List<Double> first, List<Double> second) {
        double sum = 0.0;
        int count = 0;
        for (double value : first) { sum += value; count++; }
        for (double value : second) { sum += value; count++; }
        return count == 0 ? null : sum / count;
    }

    private static Double meanOrNull(Double first, Double second) {
        if (first == null) return second;
        if (second == null) return first;
        return (first + second) / 2.0;
    }

    private static Double distanceOrNull(Point first, Point second) {
        return first == null || second == null ? null : distance(first, second);
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x() - second.x(), first.y() - second.y());
    }

    private static boolean finiteNonNegative(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0;
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
