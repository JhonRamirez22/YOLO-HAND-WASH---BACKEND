package com.handwash.recognition;

import java.util.List;

/** Six small Strategy implementations. Scores are heuristics, never probabilities. */
public final class StepRecognizerStrategies {
    private StepRecognizerStrategies() {}

    public static List<StepRecognizer> all() {
        return List.of(new PasoPalmasRecognizer(), new PasoDorsosRecognizer(),
            new PasoEntrelazadosRecognizer(), new PasoNudillosRecognizer(),
            new PasoPulgaresRecognizer(), new PasoYemasRecognizer());
    }

    private static double motion(StepRecognizer.TemporalEvidence temporal) {
        double relative = clamp(temporal.meanRelativeMotion() / 0.06);
        double openCv = temporal.meanOpenCvResidual() == null ? 0.0
            : clamp(temporal.meanOpenCvResidual() / 0.08);
        return Math.max(relative, openCv);
    }

    private static double close(double normalizedDistance) {
        return clamp((1.4 - normalizedDistance) / 1.2);
    }

    private static double proximity(Double normalizedDistance, double limit) {
        return normalizedDistance == null ? 0.0 : clamp((limit - normalizedDistance) / limit);
    }

    private static double rhythm(StepRecognizer.TemporalEvidence temporal) {
        return temporal.intervals() < 2 ? 0.0
            : clamp(temporal.directionReversals() / Math.max(1.0, temporal.intervals() / 2.0));
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    public static final class PasoPalmasRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.PALMAS; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            double score = 0.35 * motion(temporal) + 0.25 * rhythm(temporal)
                + 0.20 * close(frame.handDistanceNormalized())
                + 0.20 * clamp((frame.extendedFingerRatio() - 0.7) / 1.0);
            return new Evaluation(movement(), score,
                "movimiento relativo/óptico + ritmo + proximidad + extensión de dedos");
        }
    }

    public static final class PasoDorsosRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.DORSOS; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            return new Evaluation(movement(), 0.0,
                "ABSTAIN: la pose 2D no observa qué superficie de la mano está en contacto");
        }
    }

    public static final class PasoEntrelazadosRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.INTERDIGITALES; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            double crossings = clamp(frame.crossHandFingerIntersections() / 2.0);
            double score = 0.40 * crossings + 0.35 * motion(temporal)
                + 0.25 * close(frame.handDistanceNormalized());
            return new Evaluation(movement(), score,
                "cruces 2D de segmentos de dedos + movimiento + proximidad; solapamiento puede confundir");
        }
    }

    public static final class PasoNudillosRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.NUDILLOS; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            double score = 0.40 * frame.flexionRatio() + 0.35 * motion(temporal)
                + 0.25 * close(frame.handDistanceNormalized());
            return new Evaluation(movement(), score,
                "flexión observada + movimiento + proximidad; no acredita superficie de contacto");
        }
    }

    public static final class PasoPulgaresRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.PULGARES; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            double thumbProximity = proximity(frame.thumbToOppositePalmNormalized(), 1.25);
            double thumbArc = clamp(temporal.meanThumbAngularTravel() / 0.8);
            double score = 0.40 * thumbProximity + 0.35 * thumbArc
                + 0.25 * motion(temporal);
            return new Evaluation(movement(), score,
                "trayectoria angular del pulgar + distancia a palma opuesta + movimiento");
        }
    }

    public static final class PasoYemasRecognizer implements StepRecognizer {
        @Override public Movement movement() { return Movement.YEMAS; }
        @Override public Evaluation evaluate(HandPoseGeometryExtractor.Features frame,
                                             TemporalEvidence temporal) {
            double tipProximity = proximity(frame.fingertipsToOppositePalmNormalized(), 1.2);
            double score = 0.40 * tipProximity + 0.25 * frame.flexionRatio()
                + 0.20 * motion(temporal) + 0.15 * close(frame.handDistanceNormalized());
            return new Evaluation(movement(), score,
                "puntas próximas a palma opuesta + flexión + movimiento; contacto no observable");
        }
    }
}
