package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class CompleteState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return true; }

}
