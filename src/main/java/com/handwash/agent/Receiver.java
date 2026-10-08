package com.handwash.agent;

import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingStep;
import com.handwash.model.OmsAction;
import com.handwash.model.SoapRegion;
import com.handwash.model.SoapEvidence;
import com.handwash.model.MovementEvidence;
import com.handwash.observer.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import jakarta.annotation.PostConstruct;

@Component
public class Receiver extends Subject {
    private static final Logger log = LoggerFactory.getLogger(Receiver.class);
    private static final long MONOTONIC_ORIGIN_NANOS = System.nanoTime();

    @Value("${handwash.receptor.confidence-threshold:0.35}")
    private float confidenceThreshold = 0.35f;

    @Value("${handwash.receptor.oms-confidence-threshold:0.6}")
    private float omsConfidenceThreshold = 0.6f;

    @PostConstruct
    void validarUmbralConfianza() {
        if (!Float.isFinite(confidenceThreshold)
            || confidenceThreshold < 0.0f || confidenceThreshold > 1.0f) {
            throw new IllegalStateException("handwash.receptor.confidence-threshold debe estar entre 0 y 1");
        }
        if (!Float.isFinite(omsConfidenceThreshold)
            || omsConfidenceThreshold < 0.0f || omsConfidenceThreshold > 1.0f) {
            throw new IllegalStateException("handwash.receptor.oms-confidence-threshold debe estar entre 0 y 1");
        }
    }

    public DetectionEvent recibir(String sessionId, String claseDetectada, float confianza) {
        return recibir(new DetectionEvent(
            sessionId,
            claseDetectada,
            confianza,
            Instant.now().toString()
        ));
    }

    public DetectionEvent recibir(DetectionEvent entrada) {
        String error = validarEstructura(entrada);
        if (error != null) {
            // This compatibility entry point may also be called once per frame;
            // callers receive the validation outcome directly, so avoid turning
            // transient camera evidence gaps into a WARN log storm.
            log.debug("Detección inválida: {}", error);
            return null;
        }
        return recibirValidada(entrada);
    }

    /** Llamar solo después de validarEstructura en una frontera de aplicación. */
    public DetectionEvent recibirValidada(DetectionEvent entrada) {
        float confianza = entrada.getConfianza();
        HandwashingStep paso = entrada.getPasoLavadoResuelto();
        OmsAction accionOms = entrada.getAccionOmsResuelta();
        float umbralConfianza = paso != null ? confidenceThreshold : omsConfidenceThreshold;

        // Timestamp even confidence-filtered observations so they cannot be
        // replayed later with an older server time and rewind session tracking.
        entrada.setTimestamp(Instant.now().toString());
        long processingAtNanos = System.nanoTime();
        long ingressAtNanos = entrada.serverIngressAtMonotonicNanosOr(processingAtNanos);
        long receivedAtNanos = processingAtNanos - ingressAtNanos >= 0L
            ? ingressAtNanos : processingAtNanos;
        long elapsedFromOriginNanos = receivedAtNanos - MONOTONIC_ORIGIN_NANOS;
        if (elapsedFromOriginNanos <= 0L) {
            elapsedFromOriginNanos = processingAtNanos - MONOTONIC_ORIGIN_NANOS;
        }
        entrada.setServerReceivedAtMonotonicMs(Math.max(1L, elapsedFromOriginNanos / 1_000_000L));

        if (confianza < umbralConfianza) {
            log.debug("Confianza {} por debajo del umbral {} para {}, descartando",
                confianza, umbralConfianza, paso != null ? "fricción" : "acción OMS");
            return null;
        }

        // Reutiliza el DTO ya validado: la ruta de cámara no necesita duplicar
        // el evento ni su mapa opcional de evidencias.
        entrada.setClaseDetectada(paso != null ? paso.getClaseModelo() : accionOms.getClaseModelo());

        // A 10–30 FPS este mensaje sería demasiado costoso y ruidoso.
        log.debug("Detección recibida: {} (confianza: {})",
            paso != null ? paso.getNombre() : accionOms.getNombre(), confianza);
        notifyObservers(entrada);
        return entrada;
    }

    /** Invalid structure is a client error; below-threshold events are never published to observers. */
    public String validarEstructura(DetectionEvent entrada) {
        if (entrada == null || entrada.getSessionId() == null || entrada.getSessionId().isBlank()) {
            return "sessionId es obligatorio";
        }
        if (entrada.getConfianza() == null) return "confianza es obligatoria";
        if (!Float.isFinite(entrada.getConfianza()) || entrada.getConfianza() < 0.0f
            || entrada.getConfianza() > 1.0f) return "confianza debe estar entre 0 y 1";
        HandwashingStep paso = entrada.getPasoLavadoResuelto();
        OmsAction accion = entrada.getAccionOmsResuelta();
        if (entrada.getEvidenciaMovimiento() != null) {
            String errorMovimiento = entrada.getErrorEvidenciaMovimiento();
            if (errorMovimiento != null) return "evidenciaMovimiento." + errorMovimiento;
            if (entrada.getFrameSequence() != null
                && !entrada.getFrameSequence().equals(entrada.getEvidenciaMovimiento().secuencia())) {
                return "evidenciaMovimiento.secuencia no coincide con frameSequence";
            }
        }
        if (paso == null && accion == null) {
            return "claseDetectada desconocida";
        }
        // A contamination alert is itself fail-closed control evidence. Requiring
        // bilateral pose here would discard the alert precisely when occlusion or
        // pose failure prevents measuring both hands. Ordinary protocol phases
        // still require fresh bilateral spatial evidence.
        if (accion != null && !accion.esSinEvidencia() && !accion.esRiesgo()) {
            var evidencia = entrada.getEvidenciaMovimiento();
            if (evidencia == null) {
                return "acciones OMS requieren pose bilateral y medición espacial reciente";
            }
            if (evidencia.manosVisibles() != 2) {
                return "acciones OMS requieren exactamente dos manos visibles";
            }
            if (!Boolean.TRUE.equals(evidencia.medicionValida())) {
                return "acciones OMS requieren una medición espacial válida";
            }
            if (!entrada.evidenciaMovimientoReciente()) {
                return "evidencia espacial OMS caducada (máximo "
                    + MovementEvidence.MAX_EDAD_RECIENTE_MS + " ms)";
            }
        }
        boolean hasSoapEvidence = entrada.getEvidenciaJabon() != null
            && !entrada.getEvidenciaJabon().isEmpty();
        if (hasSoapEvidence) {
            if (accion == null || !accion.permiteEvidenciaJabon()) {
                return "evidenciaJabon solo se admite entre aplicar jabón y el último frotado OMS";
            }
            Long expectedSoapSequence = entrada.getFrameSequence() != null
                ? entrada.getFrameSequence()
                : entrada.getEvidenciaMovimiento() == null ? null
                    : entrada.getEvidenciaMovimiento().secuencia();
            if (entrada.getEvidenciaJabonSecuencia() == null) {
                return "evidenciaJabonSecuencia es obligatoria";
            }
            if (expectedSoapSequence == null) {
                return "evidenciaJabon requiere una secuencia de frame verificable";
            }
            if (!expectedSoapSequence.equals(entrada.getEvidenciaJabonSecuencia())) {
                return "evidenciaJabonSecuencia no coincide con el frame de la detección";
            }
            if (entrada.getEvidenciaJabon().size() > SoapRegion.values().length) {
                return "evidenciaJabon excede las 12 regiones permitidas";
            }
            for (var item : entrada.getEvidenciaJabon().entrySet()) {
                if (SoapRegion.from(item.getKey()) == null || item.getValue() == null
                    || item.getValue().getEstado() == null) {
                    return "evidenciaJabon contiene una región o estado inválido";
                }
                if (item.getValue().getConfianza() == null) {
                    return "evidenciaJabon.confianza es obligatoria";
                }
                if (!Float.isFinite(item.getValue().getConfianza())
                    || item.getValue().getConfianza() < 0.0f || item.getValue().getConfianza() > 1.0f) {
                    return "evidenciaJabon.confianza debe estar entre 0 y 1";
                }
            }
        } else if (entrada.getEvidenciaJabonSecuencia() != null) {
            return "evidenciaJabonSecuencia requiere evidenciaJabon";
        }
        if (entrada.getTimestamp() == null || entrada.getTimestamp().isBlank()) {
            return "timestamp es obligatorio";
        }
        try {
            long diferenciaMs = Instant.parse(entrada.getTimestamp()).toEpochMilli()
                - System.currentTimeMillis();
            if (diferenciaMs > 30_000L || diferenciaMs < -60_000L) {
                return "timestamp fuera de ventana (-60s, +30s)";
            }
        } catch (RuntimeException invalidTimestamp) {
            return "timestamp debe ser ISO-8601";
        }
        return null;
    }

}
