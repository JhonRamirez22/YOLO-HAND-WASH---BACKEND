package com.handwash.strategy;

import com.handwash.model.ProtocolType;
import com.handwash.decorator.strategy.HandwashingMetricsDecoratorFactory;
import com.handwash.service.HandwashMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Creates the validation strategy once when a session is created. */
@Component
public class ValidationRuleStrategyFactory {
    private final HandwashingMetricsDecoratorFactory decorador;
    private final Map<ProtocolType, ProtocolCreator> creadores = Map.of(
        ProtocolType.CLINICO_QUIRURGICO, new ProtocolCreator.SixtySecondGoalCreator(),
        ProtocolType.DOMESTICO, new ProtocolCreator.FortySecondGoalCreator());

    public ValidationRuleStrategyFactory() {
        this(new HandwashingMetricsDecoratorFactory(HandwashMetrics.noop()));
    }

    @Autowired
    public ValidationRuleStrategyFactory(HandwashingMetricsDecoratorFactory decorador) {
        this.decorador = decorador;
    }

    public ValidationRuleStrategy crear(ProtocolType protocolo) {
        if (protocolo == null) {
            throw new IllegalArgumentException("El protocolo es obligatorio");
        }
        ProtocolCreator creador = creadores.get(protocolo);
        if (creador == null) throw new IllegalArgumentException("Protocolo sin creador registrado");
        return decorador.decorar(creador.crear(), protocolo);
    }
}
