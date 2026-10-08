package com.handwash.state;

import com.handwash.model.PasoLavado;

public class PalmasState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_2_DORSOS) {
            return new DorsosState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_1_PALMAS; }

    @Override
    public boolean isCompletado() { return false; }

}
