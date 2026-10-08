package com.handwash.decorator.strategy;

import com.handwash.model.HandwashingStep;
import com.handwash.model.ProtocolType;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.ValidationRuleStrategy;

import java.util.List;

/** Adds low-cardinality decision telemetry without changing the wrapped rule. */
public final class HandwashingStrategyMetricsDecorator implements ValidationRuleStrategy {
    private final ValidationRuleStrategy delegate;
    private final ProtocolType protocol;
    private final HandwashMetrics metrics;

    public HandwashingStrategyMetricsDecorator(ValidationRuleStrategy delegate,
                                             ProtocolType protocol,
                                             HandwashMetrics metrics) {
        this.delegate = delegate;
        this.protocol = protocol;
        this.metrics = metrics;
    }

    @Override
    public boolean validarTiempoPaso(HandwashingStep paso, long tiempoAcumuladoMs) {
        boolean accepted = delegate.validarTiempoPaso(paso, tiempoAcumuladoMs);
        metrics.recordRuleDecision(protocol, paso, accepted);
        return accepted;
    }

    @Override public long getTiempoRequeridoPaso(HandwashingStep paso) {
        return delegate.getTiempoRequeridoPaso(paso);
    }
    @Override public long getDuracionTotalMs() { return delegate.getDuracionTotalMs(); }
    @Override public long getDuracionMinimaFaseOmsMs() { return delegate.getDuracionMinimaFaseOmsMs(); }
    @Override public String getMetodoObjetivo() { return delegate.getMetodoObjetivo(); }
    @Override public String getAlcanceEvaluacion() { return delegate.getAlcanceEvaluacion(); }
    @Override public boolean procedimientoCompletoValidado() {
        return delegate.procedimientoCompletoValidado();
    }
    @Override public List<String> accionesNoDetectadas() { return delegate.accionesNoDetectadas(); }
}
