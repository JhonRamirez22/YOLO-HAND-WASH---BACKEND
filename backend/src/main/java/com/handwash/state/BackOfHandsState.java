package com.handwash.state;

import com.handwash.model.WashStep;

public class BackOfHandsState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_3_INTERDIGITALES) {
            return new InterlacedFingersState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_2_DORSOS; }

    @Override
    public boolean isCompletado() { return false; }

}
