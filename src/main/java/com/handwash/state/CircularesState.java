package com.handwash.state;

import com.handwash.model.PasoLavado;

public class CircularesState implements PasoLavadoState {
    @Override
    public PasoLavadoState procesarDeteccion(PasoLavado claseDetectada) {
        // El último paso no se completa por recibir una clase cualquiera.
        // SesionLavado lo finaliza cuando Strategy confirma el tiempo mínimo.
        return this;
    }

    @Override
    public PasoLavado getPasoActual() { return PasoLavado.PASO_7_CIRCULARES; }

    @Override
    public boolean isCompletado() { return false; }

}
