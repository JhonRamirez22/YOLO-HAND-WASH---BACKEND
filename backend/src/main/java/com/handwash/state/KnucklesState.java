package com.handwash.state;

import com.handwash.model.WashStep;

public class KnucklesState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_5_PULGAR) {
            return new ThumbState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_4_NUDILLOS; }

    @Override
    public boolean isCompletado() { return false; }

}
