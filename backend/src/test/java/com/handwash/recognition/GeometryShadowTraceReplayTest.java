package com.handwash.recognition;

import com.handwash.model.HandPoseEvidence;
import com.handwash.service.OpenCvHandMotionEstimator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Numerical trace replay; labels are inspected only after each prediction is produced. */
class GeometryShadowTraceReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double LABEL_SETTLE_SECONDS = 0.5;

    private record Label(double at, String value) {}
    private record Counts(long tp, long fp, long fn, long tn) {
        Counts add(boolean positive, boolean predicted) {
            if (positive && predicted) return new Counts(tp + 1, fp, fn, tn);
            if (!positive && predicted) return new Counts(tp, fp + 1, fn, tn);
            if (positive) return new Counts(tp, fp, fn + 1, tn);
            return new Counts(tp, fp, fn, tn + 1);
        }
        long total() { return tp + fp + fn + tn; }
        double precision() { return tp + fp == 0 ? 0.0 : (double) tp / (tp + fp); }
        double recall() { return tp + fn == 0 ? 0.0 : (double) tp / (tp + fn); }
        double specificity() { return tn + fp == 0 ? 0.0 : (double) tn / (tn + fp); }
        double accuracy() { return total() == 0 ? 0.0 : (double) (tp + tn) / total(); }
        double macroF1() {
            double positiveF1 = f1(precision(), recall());
            double negativePrecision = tn + fn == 0 ? 0.0 : (double) tn / (tn + fn);
            return (positiveF1 + f1(negativePrecision, specificity())) / 2.0;
        }
        private static double f1(double precision, double recall) {
            return precision + recall == 0.0 ? 0.0
                : 2.0 * precision * recall / (precision + recall);
        }
    }

    @Test
    void replaysExistingNumericTracesAndPrintsAnAblationWithoutTuningThresholds() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("handwash.geometry.trace-replay"),
            "Set handwash.geometry.trace-replay=true to run the dataset diagnostic.");
        Path directory = Path.of(System.getProperty("handwash.geometry.trace-dir",
            "../datasets/traces")).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "Trace directory not found: " + directory);
        List<Path> paths;
        try (var files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                .sorted().toList();
        }
        assertFalse(paths.isEmpty(), "No JSONL traces found in " + directory);

        Counts classifier = new Counts(0, 0, 0, 0);
        Counts staticGeometry = new Counts(0, 0, 0, 0);
        Counts staticHybrid = new Counts(0, 0, 0, 0);
        Counts geometry = new Counts(0, 0, 0, 0);
        Counts geometryPaired = new Counts(0, 0, 0, 0);
        Counts hybrid = new Counts(0, 0, 0, 0);
        long exactPairs = 0L, geometryEvidenceFrames = 0L, geometryDecisions = 0L;
        long staticGeometryDecisions = 0L, staticHybridDecisions = 0L;
        long geometryPairedDecisions = 0L;
        long labeledPoseFrames = 0L, openCvValidFrames = 0L;
        List<Long> latencies = new ArrayList<>();
        List<Long> openCvValidLatencies = new ArrayList<>();
        double totalTraceSeconds = 0.0;
        long tracePoseFrames = 0L;

        for (Path path : paths) {
            List<String> lines = Files.readAllLines(path);
            List<Label> labels = readLabels(lines);
            String session = path.getFileName().toString();
            GeometryShadowRecognizer recognizer = new GeometryShadowRecognizer();
            OpenCvHandMotionEstimator openCv = new OpenCvHandMotionEstimator();
            Set<Long> poseSequences = new HashSet<>();
            long firstTraceCapture = Long.MAX_VALUE, lastTraceCapture = Long.MIN_VALUE;
            for (String line : lines) {
                JsonNode row = JSON.readTree(line);
                if (!"SAMPLE".equals(row.path("type").asText())) continue;
                JsonNode diagnostic = row.path("diagnostics");
                long sequence = diagnostic.path("poseSequence").asLong(-1L);
                double captureSeconds = diagnostic.path("poseCapturedMonotonic").asDouble(Double.NaN);
                if (sequence < 0L || !Double.isFinite(captureSeconds) || !poseSequences.add(sequence)) continue;
                long captureNanos = (long) (captureSeconds * 1_000_000_000.0);
                HandPoseEvidence pose = pose(diagnostic);
                firstTraceCapture = Math.min(firstTraceCapture, captureNanos);
                lastTraceCapture = Math.max(lastTraceCapture, captureNanos);
                tracePoseFrames++;
                long started = System.nanoTime();
                OpenCvHandMotionEstimator.Estimate estimate = openCv.observe(session, "trace",
                    sequence, captureNanos, pose);
                Double residual = estimate.valid() ? estimate.residualNormalized() : null;
                String producerCandidate = diagnostic.path("candidate").isTextual()
                    ? diagnostic.path("candidate").asText() : null;
                GeometryShadowSnapshot prediction = recognizer.observe(new HandPoseObservation(
                    session, "trace", sequence, captureNanos, pose, residual,
                    StepRecognizer.Movement.fromProducerCandidate(producerCandidate) == null
                        ? producerCandidate : StepRecognizer.Movement.fromProducerCandidate(producerCandidate).name()));
                long elapsedNanos = System.nanoTime() - started;
                latencies.add(elapsedNanos);
                if (estimate.valid()) {
                    openCvValidFrames++;
                    openCvValidLatencies.add(elapsedNanos);
                }
                String label = labelAt(labels, captureSeconds);
                if (!label.equals("PALMAS") && !label.equals("QUIETAS")) continue;
                labeledPoseFrames++;
                boolean positive = label.equals("PALMAS");
                boolean hasDecision = "CANDIDATE".equals(prediction.status());
                boolean geometryPalm = hasDecision && "PALMAS".equals(prediction.proposedMovement());
                if (hasDecision) {
                    geometryDecisions++;
                    geometry = geometry.add(positive, geometryPalm);
                }
                if (hasDecision || "AMBIGUOUS".equals(prediction.status())) geometryEvidenceFrames++;

                double stepSeconds = diagnostic.path("stepCapturedMonotonic").asDouble(Double.NaN);
                long stepSequence = diagnostic.path("stepSequence").asLong(-1L);
                if (sequence != stepSequence || !Double.isFinite(stepSeconds)
                    || Math.abs(stepSeconds - captureSeconds) > 1e-9) continue;
                JsonNode scores = diagnostic.path("classifierScores");
                if (!scores.isObject() || !scores.path("01_palm_to_palm").isNumber()) continue;
                exactPairs++;
                double palmScore = scores.path("01_palm_to_palm").asDouble();
                String topClass = "";
                double topScore = Double.NEGATIVE_INFINITY;
                var iterator = scores.propertyStream().iterator();
                while (iterator.hasNext()) {
                    var field = iterator.next();
                    if (field.getValue().isNumber() && field.getValue().asDouble() > topScore) {
                        topClass = field.getKey();
                        topScore = field.getValue().asDouble();
                    }
                }
                boolean classifierPalm = "01_palm_to_palm".equals(topClass) && palmScore >= 0.75;
                classifier = classifier.add(positive, classifierPalm);
                String staticMovement = staticGeometryCandidate(pose);
                if (staticMovement != null) {
                    staticGeometryDecisions++;
                    boolean staticPalm = "PALMAS".equals(staticMovement);
                    staticGeometry = staticGeometry.add(positive, staticPalm);
                    staticHybridDecisions++;
                    staticHybrid = staticHybrid.add(positive, classifierPalm && staticPalm);
                }
                if (hasDecision) {
                    geometryPairedDecisions++;
                    geometryPaired = geometryPaired.add(positive, geometryPalm);
                    hybrid = hybrid.add(positive, classifierPalm && geometryPalm);
                }
            }
            if (firstTraceCapture != Long.MAX_VALUE && lastTraceCapture > firstTraceCapture) {
                totalTraceSeconds += (lastTraceCapture - firstTraceCapture) / 1_000_000_000.0;
            }
            openCv.removeSession(session);
            recognizer.removeSession(session);
        }

        assertTrue(labeledPoseFrames > 0, "No PALMAS/QUIETAS pose samples were evaluable");
        System.out.printf(java.util.Locale.ROOT,
            "TRACE_ABLATION labels=PALMAS_vs_QUIETAS classifier_threshold=0.75 geometry_floor=0.55 "
                + "window_ms=500..1500 (fixed, not tuned)%n"
                + "geometry_all_labeled n=%d evidence_coverage=%d/%.4f decision_coverage=%d/%.4f "
                + "decided_confusion=%s precision=%.4f recall=%.4f specificity=%.4f "
                + "accuracy=%.4f macro_f1=%.4f%n"
                + "exact_paired_frames n=%d classifier=%s precision=%.4f recall=%.4f "
                + "specificity=%.4f accuracy=%.4f macro_f1=%.4f%n"
                + "static_geometry_decisions=%d geometry=%s precision=%.4f recall=%.4f "
                + "specificity=%.4f accuracy=%.4f macro_f1=%.4f static_hybrid_AND=%s "
                + "precision=%.4f recall=%.4f specificity=%.4f accuracy=%.4f macro_f1=%.4f%n"
                + "exact_paired_geometry_decisions=%d geometry=%s precision=%.4f recall=%.4f "
                + "specificity=%.4f accuracy=%.4f macro_f1=%.4f hybrid_AND=%s "
                + "precision=%.4f recall=%.4f specificity=%.4f accuracy=%.4f macro_f1=%.4f%n"
                + "trace_pose_rate_hz=%.3f calls=%d all_call_ms_p50=%.4f p95=%.4f "
                + "opencv_valid_pairs=%d valid_pair_ms_p50=%.4f p95=%.4f clinical_validation=false%n",
            labeledPoseFrames, geometryEvidenceFrames,
            (double) geometryEvidenceFrames / labeledPoseFrames,
            geometryDecisions, (double) geometryDecisions / labeledPoseFrames,
            geometry, geometry.precision(), geometry.recall(), geometry.specificity(),
            geometry.accuracy(), geometry.macroF1(), exactPairs,
            classifier, classifier.precision(), classifier.recall(), classifier.specificity(),
            classifier.accuracy(), classifier.macroF1(), staticGeometryDecisions,
            staticGeometry, staticGeometry.precision(), staticGeometry.recall(),
            staticGeometry.specificity(), staticGeometry.accuracy(), staticGeometry.macroF1(),
            staticHybrid, staticHybrid.precision(), staticHybrid.recall(),
            staticHybrid.specificity(), staticHybrid.accuracy(), staticHybrid.macroF1(),
            geometryPairedDecisions,
            geometryPaired, geometryPaired.precision(), geometryPaired.recall(),
            geometryPaired.specificity(), geometryPaired.accuracy(), geometryPaired.macroF1(),
            hybrid, hybrid.precision(), hybrid.recall(), hybrid.specificity(),
            hybrid.accuracy(), hybrid.macroF1(), totalTraceSeconds > 0.0
                ? tracePoseFrames / totalTraceSeconds : 0.0, latencies.size(),
            percentileMs(latencies, 0.50), percentileMs(latencies, 0.95), openCvValidFrames,
            percentileMs(openCvValidLatencies, 0.50), percentileMs(openCvValidLatencies, 0.95));
        assertFalse(hybrid.total() == 0, "No exact paired rows available for fusion comparison");
    }

    private static List<Label> readLabels(List<String> lines) throws Exception {
        List<Label> labels = new ArrayList<>();
        for (String line : lines) {
            JsonNode row = JSON.readTree(line);
            if ("LABEL".equals(row.path("type").asText())
                && row.path("monotonicSeconds").isNumber() && row.path("label").isTextual()) {
                labels.add(new Label(row.path("monotonicSeconds").asDouble(),
                    row.path("label").asText()));
            }
        }
        labels.sort(Comparator.comparingDouble(Label::at));
        return labels;
    }

    private static String labelAt(List<Label> labels, double sampleTime) {
        Label label = currentLabel(labels, sampleTime);
        return label == null || sampleTime - label.at() < LABEL_SETTLE_SECONDS ? "UNKNOWN" : label.value();
    }

    /** Static-feature ablation: same strategies and score floor, but no temporal evidence. */
    private static String staticGeometryCandidate(HandPoseEvidence pose) {
        var features = new HandPoseGeometryExtractor().extract(pose, null);
        if (features.isEmpty()) return null;
        var noTemporal = new StepRecognizer.TemporalEvidence(0.0, 0, 0.0, null, 0);
        List<StepRecognizer.Evaluation> evaluations = StepRecognizerStrategies.all().stream()
            .map(strategy -> strategy.evaluate(features.get(), noTemporal))
            .sorted(Comparator.comparingDouble(StepRecognizer.Evaluation::evidenceScore).reversed())
            .toList();
        if (evaluations.isEmpty()) return null;
        var best = evaluations.get(0);
        double second = evaluations.size() > 1 ? evaluations.get(1).evidenceScore() : 0.0;
        return best.evidenceScore() >= GeometryShadowRecognizer.MIN_SCORE
            && best.evidenceScore() - second >= GeometryShadowRecognizer.MIN_MARGIN
            ? best.movement().name() : null;
    }

    private static Label currentLabel(List<Label> labels, double sampleTime) {
        Label selected = null;
        for (Label label : labels) {
            if (label.at() > sampleTime) break;
            selected = label;
        }
        return selected;
    }

    private static HandPoseEvidence pose(JsonNode diagnostic) {
        JsonNode points = diagnostic.path("keypoints");
        JsonNode boxes = diagnostic.path("boxes");
        if (!points.isArray() || points.size() != 2 || !boxes.isArray() || boxes.size() != 2) return null;
        Double[][][] mappedPoints = new Double[2][21][3];
        Double[][] mappedBoxes = new Double[2][4];
        double maxX = 1.0, maxY = 1.0;
        for (int hand = 0; hand < 2; hand++) {
            if (!points.get(hand).isArray() || points.get(hand).size() != 21
                || !boxes.get(hand).isArray() || boxes.get(hand).size() != 4) return null;
            for (int point = 0; point < 21; point++) {
                JsonNode values = points.get(hand).get(point);
                if (!values.isArray() || values.size() != 3) return null;
                for (int axis = 0; axis < 3; axis++) {
                    if (!values.get(axis).isNumber()) return null;
                    mappedPoints[hand][point][axis] = values.get(axis).asDouble();
                }
                maxX = Math.max(maxX, mappedPoints[hand][point][0]);
                maxY = Math.max(maxY, mappedPoints[hand][point][1]);
            }
            for (int coordinate = 0; coordinate < 4; coordinate++) {
                if (!boxes.get(hand).get(coordinate).isNumber()) return null;
                mappedBoxes[hand][coordinate] = boxes.get(hand).get(coordinate).asDouble();
            }
            maxX = Math.max(maxX, mappedBoxes[hand][2]);
            maxY = Math.max(maxY, mappedBoxes[hand][3]);
        }
        int width = Math.max(1, (int) Math.ceil(maxX) + 1);
        int height = Math.max(1, (int) Math.ceil(maxY) + 1);
        var result = new HandPoseEvidence(mappedPoints, mappedBoxes, width, height);
        return result.isStructurallyValid() ? result : null;
    }

    private static double percentileMs(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0.0;
        List<Long> ordered = values.stream().sorted().toList();
        int index = Math.min(ordered.size() - 1,
            (int) Math.ceil(percentile * ordered.size()) - 1);
        return ordered.get(index) / 1_000_000.0;
    }
}
