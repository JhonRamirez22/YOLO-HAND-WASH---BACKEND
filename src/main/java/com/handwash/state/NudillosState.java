package com.handwash.state;

import com.handwash.model.PasoLavado;

public class NudillosState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        if (claseDetectada == PasoLavado.PASO_5_PULGAR) {
            return new PulgarState();
        }
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_4_NUDILLOS; }

    @Override
    public boolean isCompletado() { return false; }

}
