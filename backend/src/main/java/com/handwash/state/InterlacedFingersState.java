package com.handwash.state;

import com.handwash.model.WashStep;

public class InterlacedFingersState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_4_NUDILLOS) {
            return new KnucklesState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_3_INTERDIGITALES; }

    @Override
    public boolean isCompletado() { return false; }

}
