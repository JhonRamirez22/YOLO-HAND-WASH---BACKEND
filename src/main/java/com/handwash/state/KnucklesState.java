package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class KnucklesState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_5_PULGAR) {
            return new ThumbState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_4_NUDILLOS; }

    @Override
    public boolean isCompletado() { return false; }

}
