package com.handwash.recognition;

import com.handwash.model.WashStep;

import java.util.Objects;

/**
 * Per-session shadow run: feeds the decoder Strategy and compares it with the latest
 * step the producer cascade proposed. Only numbers are kept; nothing is persisted.
 */
public final class ShadowSequenceComparator {
    /** A cascade proposal older than this no longer describes the current movement. */
    static final long VENTANA_CASCADA_MS = 700L;
    private final SequenceDecoderStrategy decodificador;
    private WashStep ultimaCascada;
    private long ultimaCascadaMs = Long.MIN_VALUE;
    private int observaciones;

    public ShadowSequenceComparator(SequenceDecoderStrategy decodificador) {
        this.decodificador = Objects.requireNonNull(decodificador);
    }

    public synchronized void registrarPropuestaCascada(WashStep paso, long instanteMs) {
        if (paso == null || paso.getNumero() < 1 || paso.getNumero() > 6) return;
        ultimaCascada = paso;
        ultimaCascadaMs = instanteMs;
    }

    public synchronized ShadowSequenceSnapshot observar(ClassifierDistribution distribucion,
                                                         long instanteMs) {
        DecoderDecision decision = decodificador.observar(distribucion, instanteMs);
        observaciones++;
        WashStep cascada = ultimaCascadaMs != Long.MIN_VALUE
            && instanteMs - ultimaCascadaMs >= 0 && instanteMs - ultimaCascadaMs <= VENTANA_CASCADA_MS
            ? ultimaCascada : null;
        WashStep decodificado = decision.paso();
        ShadowSequenceComparison comparacion;
        if (decodificado == null) {
            comparacion = cascada == null ? ShadowSequenceComparison.NINGUNO
                : ShadowSequenceComparison.SOLO_CASCADA;
        } else if (cascada == null) {
            comparacion = ShadowSequenceComparison.SOLO_DECODIFICADOR;
        } else {
            comparacion = decodificado == cascada ? ShadowSequenceComparison.COINCIDEN
                : ShadowSequenceComparison.DIFIEREN;
        }
        return new ShadowSequenceSnapshot(decodificador.version(), decision.resultado().name(),
            decodificado == null ? null : decodificado.name(), decision.probabilidad(),
            cascada == null ? null : cascada.name(), comparacion, observaciones, false);
    }

    public synchronized void reiniciar() {
        decodificador.reiniciar();
        ultimaCascada = null;
        ultimaCascadaMs = Long.MIN_VALUE;
    }
}
