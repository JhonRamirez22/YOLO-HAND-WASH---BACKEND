package com.handwash.model;

/**
 * Audit classification of a finished V2 attempt. Only {@link #REPORTADO} means the
 * person left an attempt they had started without validating all six movements.
 */
public enum WashOutcome {
    /** All six movements validated and stored. */
    CORRECTO,
    /** Started, incomplete, then 10 s of confirmed absence: the person left. */
    REPORTADO,
    /** Confirmed contamination, or a wrong step held for 3 s, restarted the wash; not an abandonment. */
    REINICIADO,
    /** Technical end (session closed, backend restart, timeout); never blamed on the person. */
    INTERRUMPIDO;

    public static WashOutcome desde(String result, String reason) {
        if (result == null) return null;
        if ("FRICTION_SEQUENCE_COMPLETE".equals(result)) return CORRECTO;
        if ("RESET_RISK".equals(result)) return REINICIADO;
        return "HANDS_ABSENT".equals(reason) ? REPORTADO : INTERRUMPIDO;
    }
}
