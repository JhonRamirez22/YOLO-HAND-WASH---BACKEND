package com.handwash.strategy;

import com.handwash.model.ProtocolType;
import com.handwash.decorator.strategy.MetricsWashStrategyDecoratorFactory;
import com.handwash.service.HandwashMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates the validation strategy once when a session is created. */
@Component
public class ValidationRuleStrategyFactory {
    @org.springframework.beans.factory.annotation.Value("${handwash.validation.balanced-friction-enabled:false}")
    private boolean balancedFrictionEnabled;
    public boolean isBalancedFrictionEnabled() { return balancedFrictionEnabled; }
    private final MetricsWashStrategyDecoratorFactory decorador;
    private final Map<ProtocolType, ProtocolCreator> creadores = new EnumMap<>(ProtocolType.class);

    public ValidationRuleStrategyFactory() {
        this(new MetricsWashStrategyDecoratorFactory(HandwashMetrics.noop()));
    }

    public ValidationRuleStrategyFactory(MetricsWashStrategyDecoratorFactory decorador) {
        this(decorador, ProtocolProfileProvider.predeterminado());
    }

    @Autowired
    public ValidationRuleStrategyFactory(MetricsWashStrategyDecoratorFactory decorador,
                                          ProtocolProfileProvider perfiles) {
        this.decorador = decorador;
        creadores.put(ProtocolType.CLINICO_QUIRURGICO, new ProtocolCreator.Objetivo60Segundos());
        creadores.put(ProtocolType.DOMESTICO, new ProtocolCreator.Objetivo40Segundos());
        creadores.put(ProtocolType.AGUA_Y_JABON,
            new ProtocolCreator.PerfilAguaYJabon(Objects.requireNonNull(perfiles)));
    }

    /** The profile protocol models six independent evaluations, so it needs the V2 policy. */
    public boolean isDisponible(ProtocolType protocolo) {
        return protocolo != ProtocolType.AGUA_Y_JABON || balancedFrictionEnabled;
    }

    public List<ProtocolType> protocolosDisponibles() {
        return Arrays.stream(ProtocolType.values()).filter(this::isDisponible).toList();
    }

    public ValidationRuleStrategy crear(ProtocolType protocolo) {
        if (protocolo == null) {
            throw new IllegalArgumentException("El protocolo es obligatorio");
        }
        if (!isDisponible(protocolo)) {
            throw new IllegalArgumentException("PROTOCOLO_NO_DISPONIBLE");
        }
        ProtocolCreator creador = creadores.get(protocolo);
        if (creador == null) throw new IllegalArgumentException("Protocolo sin creador registrado");
        ValidationRuleStrategy strategy = creador.crear();
        if (balancedFrictionEnabled) strategy = new BalancedFrictionStrategy(strategy);
        return decorador.decorar(strategy, protocolo);
    }
}
