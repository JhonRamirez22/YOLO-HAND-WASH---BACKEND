package com.handwash.state;

import com.handwash.model.WashStep;

public class ThumbState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_6_PUNTA_DE_DEDOS) {
            return new FingertipsState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_5_PULGAR; }

    @Override
    public boolean isCompletado() { return false; }

}
