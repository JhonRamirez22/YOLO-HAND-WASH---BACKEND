package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class BackOfHandsState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_3_INTERDIGITALES) {
            return new InterdigitalSpacesState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_2_DORSOS; }

    @Override
    public boolean isCompletado() { return false; }

}
