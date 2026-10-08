package com.handwash.state;

import com.handwash.model.PasoLavado;

public interface PasoLavadoState {
    PasoLavadoState procesarDeteccion(PasoLavado claseDetectada);
    PasoLavado getPasoActual();
    boolean isCompletado();
}
