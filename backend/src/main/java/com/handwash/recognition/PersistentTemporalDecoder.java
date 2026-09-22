package com.handwash.recognition;

import java.util.Arrays;

/**
 * Forward filter over the six movements plus "no movement". Movements are sticky
 * (switch probability grows with elapsed time) and any switch is allowed: enforcing
 * the order is the V2 policy's job, not the decoder's. Parameters are uncalibrated
 * defaults until F5 replays labelled traces.
 */
public final class PersistentTemporalDecoder implements SequenceDecoderStrategy {
    public static final String VERSION = "FILTRO_TEMPORAL_V1";
    private static final int ESTADOS = ClassifierDistribution.PASOS.size() + 1;
    private static final int SIN_PASO = ESTADOS - 1;
    private static final double MIN_VEROSIMILITUD = 1e-4;
    private final double constantePermanenciaMs;
    private final long maxHuecoMs;
    private final double umbralDecision;
    private final int minObservaciones;
    private final double[] creencia = new double[ESTADOS];
    private long ultimoInstanteMs = Long.MIN_VALUE;
    private int observaciones;

    public PersistentTemporalDecoder() {
        this(1_500.0, 1_000L, 0.60, 2);
    }

    public PersistentTemporalDecoder(double constantePermanenciaMs, long maxHuecoMs,
                                            double umbralDecision, int minObservaciones) {
        if (!(constantePermanenciaMs > 0) || maxHuecoMs <= 0 || !(umbralDecision > 0 && umbralDecision < 1)
            || minObservaciones < 1) {
            throw new IllegalArgumentException("Parámetros de decodificación inválidos");
        }
        this.constantePermanenciaMs = constantePermanenciaMs;
        this.maxHuecoMs = maxHuecoMs;
        this.umbralDecision = umbralDecision;
        this.minObservaciones = minObservaciones;
        reiniciar();
    }

    @Override public String version() { return VERSION; }

    @Override
    public DecoderDecision observar(ClassifierDistribution distribucion, long instanteMs) {
        if (distribucion == null) return DecoderDecision.abstencion(0.0);
        if (observaciones > 0) {
            long huecoMs = instanteMs - ultimoInstanteMs;
            if (huecoMs < 0 || huecoMs > maxHuecoMs) reiniciar();
            else transicionar(huecoMs);
        }
        ultimoInstanteMs = instanteMs;
        double total = 0.0;
        for (int i = 0; i < ESTADOS; i++) {
            creencia[i] *= Math.max(MIN_VEROSIMILITUD, verosimilitud(distribucion, i));
            total += creencia[i];
        }
        for (int i = 0; i < ESTADOS; i++) creencia[i] /= total;
        observaciones++;
        return decidir();
    }

    @Override
    public void reiniciar() {
        Arrays.fill(creencia, 1.0 / ESTADOS);
        ultimoInstanteMs = Long.MIN_VALUE;
        observaciones = 0;
    }

    private void transicionar(long huecoMs) {
        double permanencia = Math.exp(-huecoMs / constantePermanenciaMs);
        double cambio = (1.0 - permanencia) / ESTADOS;
        for (int i = 0; i < ESTADOS; i++) creencia[i] = permanencia * creencia[i] + cambio;
    }

    private static double verosimilitud(ClassifierDistribution distribucion, int estado) {
        return estado == SIN_PASO ? distribucion.sinPaso()
            : distribucion.pasos().get(ClassifierDistribution.PASOS.get(estado));
    }

    private DecoderDecision decidir() {
        int mejor = 0;
        for (int i = 1; i < ESTADOS; i++) if (creencia[i] > creencia[mejor]) mejor = i;
        double probabilidad = creencia[mejor];
        if (observaciones < minObservaciones || probabilidad < umbralDecision) {
            return DecoderDecision.abstencion(probabilidad);
        }
        if (mejor == SIN_PASO) {
            return new DecoderDecision(DecoderDecision.Resultado.SIN_PASO, null, probabilidad);
        }
        return new DecoderDecision(DecoderDecision.Resultado.PASO,
            ClassifierDistribution.PASOS.get(mejor), probabilidad);
    }
}
