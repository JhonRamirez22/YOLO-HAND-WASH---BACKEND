package com.handwash.decorator.strategy;

import com.handwash.model.PasoLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.ReglaValidacionStrategy;

import java.util.List;

/** Adds low-cardinality decision telemetry without changing the wrapped rule. */
public final class DecoradorMetricasEstrategiaLavado implements ReglaValidacionStrategy {
    private final ReglaValidacionStrategy delegate;
    private final TipoProtocolo protocol;
    private final HandwashMetrics metrics;

    public DecoradorMetricasEstrategiaLavado(ReglaValidacionStrategy delegate,
                                             TipoProtocolo protocol,
                                             HandwashMetrics metrics) {
        this.delegate = delegate;
        this.protocol = protocol;
        this.metrics = metrics;
    }

    @Override
    public boolean validarTiempoPaso(PasoLavado paso, long tiempoAcumuladoMs) {
        boolean accepted = delegate.validarTiempoPaso(paso, tiempoAcumuladoMs);
        metrics.recordRuleDecision(protocol, paso, accepted);
        return accepted;
    }

    @Override public long getTiempoRequeridoPaso(PasoLavado paso) {
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
