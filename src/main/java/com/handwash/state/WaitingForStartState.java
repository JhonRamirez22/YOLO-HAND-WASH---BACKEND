package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class WaitingForStartState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_1_PALMAS) {
            return new PalmsState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return false; }

}
