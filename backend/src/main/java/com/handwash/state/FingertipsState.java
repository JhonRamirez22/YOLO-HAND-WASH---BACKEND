package com.handwash.state;

import com.handwash.model.WashStep;

public class FingertipsState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        // No seventh friction movement is accepted by the active partial model.
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_6_PUNTA_DE_DEDOS; }

    @Override
    public boolean isCompletado() { return false; }

}
