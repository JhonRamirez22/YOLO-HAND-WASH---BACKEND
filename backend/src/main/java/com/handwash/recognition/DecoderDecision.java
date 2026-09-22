package com.handwash.recognition;

import com.handwash.model.WashStep;

/** One decoder output; {@code paso} is non-null only for {@link Resultado#PASO}. */
public record DecoderDecision(Resultado resultado, WashStep paso, double probabilidad) {
    public enum Resultado { PASO, SIN_PASO, ABSTENCION }

    public static DecoderDecision abstencion(double probabilidad) {
        return new DecoderDecision(Resultado.ABSTENCION, null, probabilidad);
    }
}
