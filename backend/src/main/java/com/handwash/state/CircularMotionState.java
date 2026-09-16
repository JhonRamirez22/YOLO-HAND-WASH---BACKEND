package com.handwash.state;

import com.handwash.model.WashStep;

public class CircularMotionState implements WashStepState {
    @Override
    public WashStepState procesarDeteccion(WashStep claseDetectada) {
        // El último paso no se completa por recibir una clase cualquiera.
        // WashSession lo finaliza cuando Strategy confirma el tiempo mínimo.
        return this;
    }

    @Override
    public WashStep getPasoActual() { return WashStep.PASO_7_CIRCULARES; }

    @Override
    public boolean isCompletado() { return false; }

}
