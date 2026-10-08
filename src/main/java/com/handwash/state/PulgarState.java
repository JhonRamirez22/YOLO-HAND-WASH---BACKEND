package com.handwash.state;

import com.handwash.model.PasoLavado;

public class PulgarState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_6_PUNTA_DE_DEDOS) {
            return new PuntaDedosState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_5_PULGAR; }

    @Override
    public boolean isCompletado() { return false; }

}
