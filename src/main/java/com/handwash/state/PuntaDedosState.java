package com.handwash.state;

import com.handwash.model.PasoLavado;

public class PuntaDedosState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_7_CIRCULARES) {
            return new CircularesState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_6_PUNTA_DE_DEDOS; }

    @Override
    public boolean isCompletado() { return false; }

}
