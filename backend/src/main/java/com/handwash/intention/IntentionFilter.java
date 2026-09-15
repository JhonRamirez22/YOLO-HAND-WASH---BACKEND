package com.handwash.intention;

import com.handwash.model.DetectionEvent;

/** Chain of Responsibility: cada eslabón rechaza o delega al siguiente. */
public abstract class IntentionFilter {
    private final IntentionFilter siguiente;

    protected IntentionFilter(IntentionFilter siguiente) { this.siguiente = siguiente; }

    public final String evaluar(DetectionEvent evento, boolean enProgreso) {
        String motivo = rechazar(evento, enProgreso);
        if (motivo != null) return motivo;
        return siguiente == null ? null : siguiente.evaluar(evento, enProgreso);
    }

    protected abstract String rechazar(DetectionEvent evento, boolean enProgreso);
}
