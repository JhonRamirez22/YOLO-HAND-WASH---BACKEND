package com.handwash.state;

import com.handwash.model.HandwashingStep;

public interface HandwashingStepState {
    HandwashingStepState procesarDeteccion(HandwashingStep claseDetectada);
    HandwashingStep getPasoActual();
    boolean isCompletado();
}
