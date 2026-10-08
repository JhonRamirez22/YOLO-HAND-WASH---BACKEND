package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class PalmsState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_2_DORSOS) {
            return new BackOfHandsState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_1_PALMAS; }

    @Override
    public boolean isCompletado() { return false; }

}
