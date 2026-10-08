package com.handwash.state;

import com.handwash.model.PasoLavado;

public class EsperandoInicioState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_1_PALMAS) {
            return new PalmasState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return null; }

    @Override
    public boolean isCompletado() { return false; }

}
