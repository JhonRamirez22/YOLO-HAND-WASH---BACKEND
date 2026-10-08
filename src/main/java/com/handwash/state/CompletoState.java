package com.handwash.state;

import com.handwash.model.PasoLavado;

public class CompletoState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return true; }

}
