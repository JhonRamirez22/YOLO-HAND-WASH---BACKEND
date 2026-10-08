package com.handwash.intention;

import com.handwash.model.DeteccionEvento;

/** Chain of Responsibility: cada eslabón rechaza o delega al siguiente. */
public abstract class FiltroIntencion {
    private final FiltroIntencion siguiente;

    protected FiltroIntencion(FiltroIntencion siguiente) { this.siguiente = siguiente; }

    public final String evaluar(DeteccionEvento evento, boolean enProgreso) {
        String motivo = rechazar(evento, enProgreso);
        if (motivo != null) return motivo;
        return siguiente == null ? null : siguiente.evaluar(evento, enProgreso);
    }

    protected abstract String rechazar(DeteccionEvento evento, boolean enProgreso);
}
