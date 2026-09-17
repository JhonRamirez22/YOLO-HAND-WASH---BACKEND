package com.handwash.state;

import com.handwash.model.WashStep;

public class WaitingToStartState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        if (claseDetectada == WashStep.PASO_1_PALMAS) {
            return new PalmsState();
        }
        return this;
    }

    @Override
    public WashStep getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return false; }

}
