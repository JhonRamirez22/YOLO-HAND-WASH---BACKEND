package com.handwash.state;

import com.handwash.model.HandwashingStep;

public class CircularMotionsState implements HandwashingStepState {
    @Override
    public HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada) {
        // El último paso no se completa por recibir una clase cualquiera.
        // HandwashingSession lo finaliza cuando Strategy confirma el tiempo mínimo.
        return this;
    }

    @Override
    public HandwashingStep getPasoActual() { return HandwashingStep.PASO_7_CIRCULARES; }

    @Override
    public boolean isCompletado() { return false; }

}
