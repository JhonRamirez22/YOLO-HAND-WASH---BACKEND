package com.handwash.state;

import com.handwash.model.PasoLavado;

public class InterdigitalesState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_4_NUDILLOS) {
            return new NudillosState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_3_INTERDIGITALES; }

    @Override
    public boolean isCompletado() { return false; }

}
