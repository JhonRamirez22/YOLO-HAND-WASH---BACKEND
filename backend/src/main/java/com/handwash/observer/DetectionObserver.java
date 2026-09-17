package com.handwash.observer;

import com.handwash.model.DetectionEvent;

public interface DetectionObserver {
    void onDeteccion(DetectionEvent evento);

    /** State-only refresh; it must not re-run detection or validation. */
    default void onStateChanged(String sessionId) {}
}
