package com.handwash.state;

import com.handwash.model.PasoLavado;

public class DorsosState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_3_INTERDIGITALES) {
            return new InterdigitalesState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_2_DORSOS; }

    @Override
    public boolean isCompletado() { return false; }

}
