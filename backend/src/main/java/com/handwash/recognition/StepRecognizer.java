package com.handwash.recognition;

/** Strategy contract for uncalibrated geometric hypotheses, not clinical decisions. */
public interface StepRecognizer {
    enum Movement {
        PALMAS("Palmas"), DORSOS("Dorso de las manos"),
        INTERDIGITALES("Dedos entrelazados"), NUDILLOS("Dorso de los dedos"),
        PULGARES("Pulgares"), YEMAS("Yemas de los dedos");

        private final String label;
        Movement(String label) { this.label = label; }
        public String label() { return label; }

        public static Movement fromProducerCandidate(String candidate) {
            if (candidate == null) return null;
            return switch (candidate) {
                case "PASO_1_PALMAS" -> PALMAS;
                case "PASO_2_DORSOS" -> DORSOS;
                case "PASO_3_INTERDIGITALES" -> INTERDIGITALES;
                case "PASO_4_NUDILLOS" -> NUDILLOS;
                case "PASO_5_PULGAR" -> PULGARES;
                case "PASO_6_PUNTA_DE_DEDOS" -> YEMAS;
                default -> null; // Paso 7 is deliberately not mapped to an OMS movement.
            };
        }
    }

    record TemporalEvidence(double meanRelativeMotion, int directionReversals,
                            double meanThumbAngularTravel, Double meanOpenCvResidual,
                            int intervals) {}

    record Evaluation(Movement movement, double evidenceScore, String evidence) {}

    Movement movement();

    Evaluation evaluate(HandPoseGeometryExtractor.Features frame, TemporalEvidence temporal);
}
