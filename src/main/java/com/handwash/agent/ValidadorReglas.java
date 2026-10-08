package com.handwash.agent;

import com.handwash.model.*;
import com.handwash.observer.DeteccionObserver;
import com.handwash.service.HandwashMetrics;
import com.handwash.service.SessionManager;
import com.handwash.strategy.ReglaValidacionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ValidadorReglas implements DeteccionObserver {
    private static final Logger log = LoggerFactory.getLogger(ValidadorReglas.class);

    private final SessionManager sessionManager;
    private final HandwashMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public ValidadorReglas(SessionManager sessionManager, HandwashMetrics metrics) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
    }

    public ValidadorReglas(SessionManager sessionManager) {
        this(sessionManager, HandwashMetrics.noop());
    }

    @Override
    public void onDeteccion(DeteccionEvento evento) {
        long started = System.nanoTime();
        try {
            validarDeteccion(evento);
        } finally {
            metrics.recordStrategyProcessing(System.nanoTime() - started);
        }
    }

    private void validarDeteccion(DeteccionEvento evento) {
        SesionLavado sesion = sessionManager.getSesion(evento.getSessionId());
        if (sesion == null) return;
        if ("PROTOCOLO_OMS".equals(sesion.getModoEvaluacionSeleccionado())) return;

        PasoLavado pasoValidado = sesion.getPasoPendienteValidacion();
        if (pasoValidado == null || pasoValidado == PasoLavado.FONDO) {
            return;
        }

        ReglaValidacionStrategy strategy = sesion.getEstrategia();
        long tiempoAcumulado = sesion.getTiempoPendienteValidacionMs();

        if (!strategy.validarTiempoPaso(pasoValidado, tiempoAcumulado)) {
            long tiempoRequerido = strategy.getTiempoRequeridoPaso(pasoValidado);
            sesion.registrarInfraccion(
                TipoInfraccion.TIEMPO_INSUFICIENTE,
                String.format("Paso %s: %.1fs / %.1fs requeridos",
                    pasoValidado.getNombre(),
                    tiempoAcumulado / 1000.0,
                    tiempoRequerido / 1000.0),
                pasoValidado
            );
            // La estrategia registra la infracción para el resumen, pero un
            // paso corto no bloquea la secuencia ni obliga a empezar de cero.
            log.warn("Infracción observada: paso {} con tiempo insuficiente; continúa la secuencia",
                pasoValidado.getNombre());
        }

        sesion.limpiarPasoPendienteValidacion();
    }
}
