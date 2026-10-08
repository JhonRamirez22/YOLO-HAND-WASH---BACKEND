package com.handwash.decorator.strategy;

import com.handwash.model.TipoProtocolo;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.ReglaValidacionStrategy;

/** Creates one protocol-scoped decorator for each new wash session. */
public final class FabricaDecoradorMetricasLavado {
    private final HandwashMetrics metrics;

    public FabricaDecoradorMetricasLavado(HandwashMetrics metrics) {
        this.metrics = metrics;
    }

    public ReglaValidacionStrategy decorar(ReglaValidacionStrategy strategy, TipoProtocolo protocol) {
        return new DecoradorMetricasEstrategiaLavado(strategy, protocol, metrics);
    }
}
