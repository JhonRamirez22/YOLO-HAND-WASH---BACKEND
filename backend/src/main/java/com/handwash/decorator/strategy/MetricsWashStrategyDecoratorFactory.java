package com.handwash.decorator.strategy;

import com.handwash.model.ProtocolType;
import com.handwash.service.HandwashMetrics;
import com.handwash.strategy.ValidationRuleStrategy;

/** Creates one protocol-scoped decorator for each new wash session. */
public final class MetricsWashStrategyDecoratorFactory {
    private final HandwashMetrics metrics;

    public MetricsWashStrategyDecoratorFactory(HandwashMetrics metrics) {
        this.metrics = metrics;
    }

    public ValidationRuleStrategy decorar(ValidationRuleStrategy strategy, ProtocolType protocol) {
        return new MetricsWashStrategyDecorator(strategy, protocol, metrics);
    }
}
