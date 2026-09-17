package com.handwash.state;

import com.handwash.model.WashStep;

public class PalmsState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_2_DORSOS) {
            return new BackOfHandsState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_1_PALMAS; }

    @Override
    public boolean isCompletado() { return false; }

}
