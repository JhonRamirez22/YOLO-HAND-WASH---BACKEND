package com.handwash.recognition;

import java.util.Optional;

/**
 * Veto-only use of the geometric recognizer: when it sustains a candidate that contradicts the producer's
 * classifier, the frame is withheld. It can never authorize a step; abstention or agreement keeps Java's criterion.
 */
public final class GeometryCorroboration {
    static final String REASON = "La geometría de las manos contradice al clasificador; confirmando el paso";
    private final boolean enabled;

    public GeometryCorroboration(boolean enabled) { this.enabled = enabled; }

    public Optional<String> veto(GeometryShadowSnapshot snapshot) {
        if (!enabled || snapshot == null) return Optional.empty();
        boolean contradicts = "CANDIDATE".equals(snapshot.status())
            && "DISAGREES".equals(snapshot.producerAgreement());
        return contradicts ? Optional.of(REASON) : Optional.empty();
    }
}
