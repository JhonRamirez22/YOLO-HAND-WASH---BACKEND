package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class InterdigitalSpacesState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        if (claseDetectada == HandwashingStep.PASO_4_NUDILLOS) {
            return new KnucklesState();
        }
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_3_INTERDIGITALES; }

    @Override
    public boolean isCompletado() { return false; }

}
