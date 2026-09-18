package com.handwash.decorator.strategy;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolType;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.ValidationRuleStrategy;

import java.util.List;

/** Adds low-cardinality decision telemetry without changing the wrapped rule. */
public final class MetricsWashStrategyDecorator implements ValidationRuleStrategy {
    private final ValidationRuleStrategy delegate;
    private final ProtocolType protocol;
    private final HandwashMetrics metrics;

    public MetricsWashStrategyDecorator(ValidationRuleStrategy delegate,
                                             ProtocolType protocol,
                                             HandwashMetrics metrics) {
        this.delegate = delegate;
        this.protocol = protocol;
        this.metrics = metrics;
    }

    @Override
    public boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs) {
        boolean accepted = delegate.validarTiempoPaso(paso, tiempoAcumuladoMs);
        metrics.recordRuleDecision(protocol, paso, accepted);
        return accepted;
    }

    @Override public long getTiempoRequeridoPaso(WashStep paso) {
        return delegate.getTiempoRequeridoPaso(paso);
    }
    @Override public long getDuracionTotalMs() { return delegate.getDuracionTotalMs(); }
    @Override public String getVersionPolitica() { return delegate.getVersionPolitica(); }
    @Override public List<WashStep> getPasosAcreditables() { return delegate.getPasosAcreditables(); }
    @Override public long getDuracionMinimaFriccionMs() { return delegate.getDuracionMinimaFriccionMs(); }
    @Override public long getDuracionMinimaFaseOmsMs() { return delegate.getDuracionMinimaFaseOmsMs(); }
    @Override public String getMetodoObjetivo() { return delegate.getMetodoObjetivo(); }
    @Override public String getAlcanceEvaluacion() { return delegate.getAlcanceEvaluacion(); }
    @Override public boolean procedimientoCompletoValidado() {
        return delegate.procedimientoCompletoValidado();
    }
    @Override public List<String> accionesNoDetectadas() { return delegate.accionesNoDetectadas(); }
    @Override public Integer getVersionPerfilProtocolo() { return delegate.getVersionPerfilProtocolo(); }
    @Override public long getUmbralLavadoLentoMs() { return delegate.getUmbralLavadoLentoMs(); }
}
