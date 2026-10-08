package com.handwash.agent;

import com.handwash.model.*;
import com.handwash.intention.HandwashingIntentChain;
import com.handwash.observer.DetectionObserver;
import com.handwash.service.HandwashMetrics;
import com.handwash.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SequenceEvaluator implements DetectionObserver {
    private static final Logger log = LoggerFactory.getLogger(SequenceEvaluator.class);

    private final SessionManager sessionManager;
    private final HandwashingIntentChain cadenaIntencion;
    private final HandwashMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public SequenceEvaluator(SessionManager sessionManager, HandwashingIntentChain cadenaIntencion,
                              HandwashMetrics metrics) {
        this.sessionManager = sessionManager;
        this.cadenaIntencion = cadenaIntencion;
        this.metrics = metrics;
    }

    public SequenceEvaluator(SessionManager sessionManager, HandwashingIntentChain cadenaIntencion) {
        this(sessionManager, cadenaIntencion, HandwashMetrics.noop());
    }

    public SequenceEvaluator(SessionManager sessionManager) {
        this(sessionManager, new HandwashingIntentChain(650, 650, 3, 1500, 0.75));
    }

    @Override
    public void onDeteccion(DetectionEvent evento) {
        long started = System.nanoTime();
        try {
            evaluarDeteccion(evento);
        } finally {
            metrics.recordStateProcessing(System.nanoTime() - started);
        }
    }

    private void evaluarDeteccion(DetectionEvent evento) {
        HandwashingSession sesion = sessionManager.getSesion(evento.getSessionId());
        if (sesion == null) return;

        OmsAction accionOms = evento.getAccionOmsResuelta();
        if (accionOms != null) {
            long receivedAt = parseServerTimestamp(evento);
            sesion.procesarAccionOms(
                accionOms, evento.getEvidenciaJabon(), receivedAt, evento.getConfianza());
            log.debug("Secuencia OMS evaluada: acción {} -> estado {}",
                accionOms.getNombre(), sesion.getEstadoSesion());
            return;
        }

        HandwashingStep paso = evento.getPasoLavadoResuelto();
        if (paso == null) return;

        long timestampMs = parseServerTimestamp(evento);
        if (!sesion.evaluarIntencion(evento, cadenaIntencion)) {
            metrics.recordIntentRejection(sesion.getUltimoCodigoRechazoIntencion());
            return;
        }
        sesion.procesarDeteccionSinRespuesta(paso, timestampMs, evento.getConfianza());

        log.debug("Secuencia evaluada: paso {} -> estado {}",
            paso.getNombre(), sesion.getEstadoSesion());
    }

    private long parseServerTimestamp(DetectionEvent evento) {
        long monotonicMs = evento.getServerReceivedAtMonotonicMs();
        if (monotonicMs > 0L) return monotonicMs;
        try {
            return Instant.parse(evento.getTimestamp()).toEpochMilli();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }
}
