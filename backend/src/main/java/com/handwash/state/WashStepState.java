package com.handwash.state;

import com.handwash.model.WashStep;

public interface WashStepState {
    WashStepState procesarDeteccion(WashStep claseDetectada);
    WashStep getPasoActual();
    boolean isCompletado();
}
