package com.handwash.api.v1.dto;

import com.handwash.model.WashStep;

/** Rehearsal mode: the operator confirms or corrects a detected step (steps 1-6 only). */
public record StepFeedbackRequest(Integer paso, String resultado, Integer pasoCorrecto) {
    public record Validated(WashStep step, boolean corrected, WashStep correctStep) {}

    /** Null when the request is malformed. */
    public Validated validate() {
        WashStep step = trainingStep(paso);
        boolean corrected = "CORREGIDO".equals(resultado);
        if (step == null || !corrected && !"CONFIRMADO".equals(resultado)) return null;
        WashStep correct = corrected ? trainingStep(pasoCorrecto) : null;
        if (corrected && (correct == null || correct == step)) return null;
        return new Validated(step, corrected, correct);
    }

    private static WashStep trainingStep(Integer number) {
        if (number == null || number < 1 || number > 6) return null;
        for (WashStep step : WashStep.values()) if (step.getNumero() == number) return step;
        return null;
    }
}
