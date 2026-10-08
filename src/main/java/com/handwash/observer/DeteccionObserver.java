package com.handwash.observer;

import com.handwash.model.DeteccionEvento;

public interface DeteccionObserver {
    void onDeteccion(DeteccionEvento evento);

    /** State-only refresh; it must not re-run detection or validation. */
    default void onStateChanged(String sessionId) {}
}
