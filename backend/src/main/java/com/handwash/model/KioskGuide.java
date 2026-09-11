package com.handwash.model;

/**
 * Closed catalog of kiosk instructions. Java decides the instruction from the V2
 * attempt; the kiosk only translates {@link Codigo} into short text, icon and tone.
 *
 * @param paso       movement the instruction refers to, or null
 * @param faltanteMs credited time still missing for {@code paso}, or null
 */
public record KioskGuide(Codigo codigo, String paso, Long faltanteMs, Tono tono) {
    public enum Codigo {
        /** No attempt yet: start with palm to palm. */
        INICIA_CON_PALMAS,
        /** Keep doing the current movement until its minimum is credited. */
        CONTINUA_PASO,
        /** Current movement validated: move to {@code paso}. */
        CAMBIA_A_PASO,
        /** Switched too early: return to {@code paso} for {@code faltanteMs}. */
        VUELVE_A_PASO,
        /** Out-of-order movement: do {@code paso} first. */
        SIGUE_EL_ORDEN,
        /** One or both hands are not observable. */
        MUESTRA_AMBAS_MANOS,
        /** No reliable movement observed: keep rubbing {@code paso}. */
        MANTEN_EL_MOVIMIENTO,
        /** All six movements validated and stored. */
        LAVADO_COMPLETO,
        /** The person left before completing the six movements. */
        LAVADO_INCOMPLETO,
        /** Confirmed contamination: the wash must start again. */
        REINICIA_LAVADO,
        /** Camera, network, producer or storage problem; never the person's fault. */
        ESPERA_TECNICA
    }

    public enum Tono { INFO, CORRECCION, EXITO, ALERTA, TECNICO }
}
