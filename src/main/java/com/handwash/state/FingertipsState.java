package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class FingertipsState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_7_CIRCULARES) {
            return new CircularMotionsState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_6_PUNTA_DE_DEDOS; }

    @Override
    public boolean isCompletado() { return false; }

}
