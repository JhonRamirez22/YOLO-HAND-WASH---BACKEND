package com.handwash.api.v1.mapper;

import com.handwash.api.v1.dto.OmsActionResponse;
import com.handwash.api.v1.dto.OmsProtocolResponse;
import com.handwash.api.v1.dto.ProtocolResponse;
import com.handwash.model.AccionOms;
import com.handwash.model.PasoLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.strategy.ReglaValidacionStrategy;
import com.handwash.strategy.ReglaValidacionStrategyFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps protocol-domain rules into stable, versioned HTTP response DTOs. */
@Component
public final class ProtocolCatalogMapper {
    private static final String OMS_SOURCE =
        "https://cdn.who.int/media/docs/default-source/patient-safety/como-lavarse-las-manos.pdf?sfvrsn=7004a09d_11";
    private static final String OMS_SCOPE_NOTE =
        "Catálogo de referencia; su publicación no indica que el modelo activo detecte todas las fases.";
    private static final String OMS_DURATION_NOTE =
        "La OMS indica 40–60 s para el procedimiento completo; los segundos por movimiento son reglas configuradas por el proyecto.";

    private final ReglaValidacionStrategyFactory strategyFactory;

    public ProtocolCatalogMapper(ReglaValidacionStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    public Map<String, ProtocolResponse> frictionProtocols() {
        Map<String, ProtocolResponse> responses = new LinkedHashMap<>();
        for (TipoProtocolo protocol : TipoProtocolo.values()) {
            ReglaValidacionStrategy strategy = strategyFactory.crear(protocol);
            Map<String, Long> stepTimes = new LinkedHashMap<>();
            for (PasoLavado step : PasoLavado.values()) {
                if (step != PasoLavado.FONDO) {
                    stepTimes.put(step.name(), strategy.getTiempoRequeridoPaso(step));
                }
            }
            responses.put(protocol.value(), new ProtocolResponse(
                protocol.getNombre(),
                strategy.getDuracionTotalMs(),
                stepTimes,
                "REGLA_DEL_PROYECTO_NO_UMBRAL_OMS",
                strategy.getMetodoObjetivo(),
                strategy.getAlcanceEvaluacion(),
                strategy.procedimientoCompletoValidado(),
                strategy.accionesNoDetectadas()));
        }
        return responses;
    }

    public OmsProtocolResponse soapAndWaterReference() {
        List<OmsActionResponse> actions = AccionOms.SECUENCIA.stream()
            .map(action -> new OmsActionResponse(action.getOrden(), action.getClaseModelo(),
                action.getNombre(), action.getInstruccion(), action.getClaseModelo()))
            .toList();
        return new OmsProtocolResponse(
            "JABON_Y_AGUA", 40_000, 60_000, actions,
            OMS_SCOPE_NOTE, OMS_DURATION_NOTE, OMS_SOURCE);
    }
}
