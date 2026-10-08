package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class ThumbState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_6_PUNTA_DE_DEDOS) {
            return new FingertipsState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_5_PULGAR; }

    @Override
    public boolean isCompletado() { return false; }

}
