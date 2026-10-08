package com.handwash.strategy;

import com.handwash.model.TipoProtocolo;
import com.handwash.decorator.strategy.FabricaDecoradorMetricasLavado;
import com.handwash.service.HandwashMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Creates the validation strategy once when a session is created. */
@Component
public class ReglaValidacionStrategyFactory {
    private final FabricaDecoradorMetricasLavado decorador;
    private final Map<TipoProtocolo, CreadorProtocolo> creadores = Map.of(
        TipoProtocolo.CLINICO_QUIRURGICO, new CreadorProtocolo.Objetivo60Segundos(),
        TipoProtocolo.DOMESTICO, new CreadorProtocolo.Objetivo40Segundos());

    public ReglaValidacionStrategyFactory() {
        this(new FabricaDecoradorMetricasLavado(HandwashMetrics.noop()));
    }

    @Autowired
    public ReglaValidacionStrategyFactory(FabricaDecoradorMetricasLavado decorador) {
        this.decorador = decorador;
    }

    public ReglaValidacionStrategy crear(TipoProtocolo protocolo) {
        if (protocolo == null) {
            throw new IllegalArgumentException("El protocolo es obligatorio");
        }
        CreadorProtocolo creador = creadores.get(protocolo);
        if (creador == null) throw new IllegalArgumentException("Protocolo sin creador registrado");
        return decorador.decorar(creador.crear(), protocolo);
    }
}
