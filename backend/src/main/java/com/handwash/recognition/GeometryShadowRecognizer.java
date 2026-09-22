package com.handwash.recognition;

import com.handwash.model.HandPoseEvidence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-session geometric shadow recognizer; never changes Java's state machine. */
public final class GeometryShadowRecognizer {
    private static final int MAX_OBSERVATIONS = 32;
    private static final long WINDOW_NANOS = 1_500_000_000L;
    private static final long MAX_GAP_NANOS = 650_000_000L;
    private static final long MIN_WINDOW_NANOS = 500_000_000L;
    private static final int MIN_OBSERVATIONS = 3;
    static final double MIN_SCORE = 0.55;
    static final double MIN_MARGIN = 0.08;

    private record Frame(long sequence, long atNanos, HandPoseGeometryExtractor.Features features) {}

    private static final class SessionWindow {
        private String epoch;
        private long lastSequence = -1L;
        private long lastAtNanos;
        private final Deque<Frame> frames = new ArrayDeque<>();

        private void clear() { frames.clear(); }
    }

    private final HandPoseEvidenceAdapter adapter;
    private final HandPoseGeometryExtractor extractor;
    private final List<StepRecognizer> strategies;
    private final Map<String, SessionWindow> windows = new ConcurrentHashMap<>();

    public GeometryShadowRecognizer() {
        this(new ProducerPoseEvidenceAdapter(), new HandPoseGeometryExtractor(),
            StepRecognizerStrategies.all());
    }

    GeometryShadowRecognizer(HandPoseEvidenceAdapter adapter,
                             HandPoseGeometryExtractor extractor,
                             List<StepRecognizer> strategies) {
        this.adapter = adapter;
        this.extractor = extractor;
        this.strategies = List.copyOf(strategies);
    }

    public GeometryShadowSnapshot observe(HandPoseObservation observation) {
        if (observation == null || blank(observation.sessionId()) || blank(observation.producerEpoch())
            || observation.frameSequence() < 0L || observation.observedAtNanos() <= 0L) {
            return abstain("INVALID_FRAME_IDENTITY", 0, 0L, 0.0, List.of());
        }
        SessionWindow window = windows.computeIfAbsent(observation.sessionId(), ignored -> new SessionWindow());
        synchronized (window) {
            if (!observation.producerEpoch().equals(window.epoch)) {
                window.epoch = observation.producerEpoch();
                window.lastSequence = -1L;
                window.lastAtNanos = 0L;
                window.clear();
            }
            if (observation.frameSequence() <= window.lastSequence
                || observation.observedAtNanos() <= window.lastAtNanos) {
                window.clear();
                window.lastSequence = Math.max(window.lastSequence, observation.frameSequence());
                window.lastAtNanos = Math.max(window.lastAtNanos, observation.observedAtNanos());
                return abstain("DUPLICATE_OR_OUT_OF_ORDER_FRAME", 0, 0L, 0.0, List.of());
            }
            if (window.lastAtNanos > 0L
                && observation.observedAtNanos() - window.lastAtNanos > MAX_GAP_NANOS) {
                window.clear();
            }
            window.lastSequence = observation.frameSequence();
            window.lastAtNanos = observation.observedAtNanos();

            HandPoseEvidence pose = observation.pose();
            var features = extractor.extract(pose, observation.openCvResidualNormalized());
            if (features.isEmpty()) {
                window.clear();
                return abstain(pose == null || pose.visibleHands() < 2
                    ? "BILATERAL_POSE_UNAVAILABLE" : "KEYPOINT_GEOMETRY_INCOMPLETE",
                    0, 0L, 0.0, List.of());
            }
            window.frames.addLast(new Frame(observation.frameSequence(),
                observation.observedAtNanos(), features.get()));
            while (window.frames.size() > MAX_OBSERVATIONS) window.frames.removeFirst();
            while (!window.frames.isEmpty()
                && observation.observedAtNanos() - window.frames.peekFirst().atNanos() > WINDOW_NANOS) {
                window.frames.removeFirst();
            }

            List<Frame> frames = List.copyOf(window.frames);
            long durationNanos = frames.size() < 2 ? 0L
                : frames.get(frames.size() - 1).atNanos() - frames.get(0).atNanos();
            long durationMs = Math.max(0L, durationNanos / 1_000_000L);
            double quality = features.get().keypointQuality();
            if (frames.size() < MIN_OBSERVATIONS || durationNanos < MIN_WINDOW_NANOS) {
                return abstain("TEMPORAL_WINDOW_INSUFFICIENT", frames.size(), durationMs,
                    quality, List.of());
            }

            StepRecognizer.TemporalEvidence temporal = temporalEvidence(frames);
            List<StepRecognizer.Evaluation> evaluations = strategies.stream()
                .map(strategy -> strategy.evaluate(features.get(), temporal))
                .sorted(Comparator.comparingDouble(StepRecognizer.Evaluation::evidenceScore).reversed())
                .toList();
            List<GeometryShadowSnapshot.Candidate> alternatives = evaluations.stream()
                .map(e -> new GeometryShadowSnapshot.Candidate(e.movement().name(),
                    e.evidenceScore(), e.evidence())).toList();
            StepRecognizer.Evaluation best = evaluations.get(0);
            double secondScore = evaluations.size() > 1 ? evaluations.get(1).evidenceScore() : 0.0;
            double margin = best.evidenceScore() - secondScore;
            String producerAgreement = agreement(best.evidenceScore() >= MIN_SCORE
                ? best.movement() : null,
                StepRecognizer.Movement.fromProducerCandidate(observation.producerCandidate()));
            if (best.evidenceScore() < MIN_SCORE) {
                return abstain("GEOMETRIC_EVIDENCE_BELOW_EXPERIMENTAL_FLOOR", frames.size(),
                    durationMs, quality, alternatives);
            }
            if (margin < MIN_MARGIN) {
                return new GeometryShadowSnapshot("AMBIGUOUS", null, best.evidenceScore(), margin,
                    "Varios movimientos tienen puntuación heurística similar", frames.size(),
                    durationMs, quality, producerAgreement, alternatives, false);
            }
            return new GeometryShadowSnapshot("CANDIDATE", best.movement().name(),
                best.evidenceScore(), margin, best.evidence(), frames.size(), durationMs,
                quality, producerAgreement, alternatives, false);
        }
    }

    public GeometryShadowSnapshot observe(com.handwash.model.DetectionEvent event,
                                          long serverIngressNanos, Double openCvResidualNormalized) {
        return observe(adapter.adapt(event, serverIngressNanos, openCvResidualNormalized));
    }

    public void resetSession(String sessionId) {
        SessionWindow window = windows.get(sessionId);
        if (window != null) synchronized (window) {
            window.clear();
            window.lastSequence = -1L;
            window.lastAtNanos = 0L;
        }
    }

    public GeometryShadowSnapshot resetAndAbstain(String sessionId, String reason) {
        resetSession(sessionId);
        return abstain(reason, 0, 0L, 0.0, List.of());
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) windows.remove(sessionId);
    }

    private static StepRecognizer.TemporalEvidence temporalEvidence(List<Frame> frames) {
        double movementSum = 0.0, thumbTravelSum = 0.0, residualSum = 0.0;
        int thumbPairs = 0, residualCount = 0, reversals = 0;
        boolean hasPreviousDelta = false;
        double priorDx = 0.0, priorDy = 0.0;
        for (int index = 1; index < frames.size(); index++) {
            var before = frames.get(index - 1).features();
            var after = frames.get(index).features();
            double dx = after.relativeX() - before.relativeX();
            double dy = after.relativeY() - before.relativeY();
            movementSum += Math.hypot(dx, dy);
            if (hasPreviousDelta && dx * priorDx + dy * priorDy < 0.0) reversals++;
            priorDx = dx;
            priorDy = dy;
            hasPreviousDelta = true;
            double firstAngle = angularDifference(before.thumbAngleFirst(), after.thumbAngleFirst());
            double secondAngle = angularDifference(before.thumbAngleSecond(), after.thumbAngleSecond());
            if (Double.isFinite(firstAngle) && Double.isFinite(secondAngle)) {
                thumbTravelSum += (Math.abs(firstAngle) + Math.abs(secondAngle)) / 2.0;
                thumbPairs++;
            }
            if (after.openCvResidualNormalized() != null) {
                residualSum += after.openCvResidualNormalized();
                residualCount++;
            }
        }
        int intervals = frames.size() - 1;
        return new StepRecognizer.TemporalEvidence(movementSum / Math.max(1, intervals),
            reversals, thumbPairs == 0 ? 0.0 : thumbTravelSum / thumbPairs,
            residualCount == 0 ? null : residualSum / residualCount, intervals);
    }

    private static double angularDifference(Double before, Double after) {
        if (before == null || after == null) return Double.NaN;
        double delta = after - before;
        while (delta > Math.PI) delta -= 2.0 * Math.PI;
        while (delta < -Math.PI) delta += 2.0 * Math.PI;
        return delta;
    }

    private static String agreement(StepRecognizer.Movement geometry,
                                    StepRecognizer.Movement producer) {
        if (geometry == null) return "NO_GEOMETRIC_CANDIDATE";
        if (producer == null) return "NO_COMPARABLE_PRODUCER_CLASS";
        return geometry == producer ? "AGREES" : "DISAGREES";
    }

    private static GeometryShadowSnapshot abstain(String reason, int observations,
                                                   long durationMs, double quality,
                                                   List<GeometryShadowSnapshot.Candidate> candidates) {
        return new GeometryShadowSnapshot("ABSTAIN", null, 0.0, 0.0, reason,
            observations, durationMs, quality, "UNAVAILABLE", candidates, false);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
