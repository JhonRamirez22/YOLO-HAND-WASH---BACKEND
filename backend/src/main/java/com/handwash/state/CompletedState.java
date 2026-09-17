package com.handwash.state;

import com.handwash.model.WashStep;

public class CompletedState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        return this;
    }

    @Override
    public WashStep getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return true; }

}
