package com.handwash.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class DetectionEvent {
    private String sessionId;
    private String claseDetectada;
    // Wrapper para diferenciar un campo omitido en JSON de una confianza 0.0.
    private Float confianza;
    private String timestamp;
    private String producerEpoch;
    private String eventType;
    private Long frameSequence;
    private Long controlSequence;
    private Long frameWatermark;
    /** Bilateral hand count for the authenticated v2 PRESENCE event only. */
    private Integer presenceHandsVisible;
    /** Client-measured capture age, diagnostic only; never used for clinical timing. */
    private Long captureAgeMs;
    private MovementEvidence evidenciaMovimiento;
    /** Transient pose features used only for server-side OpenCV validation. */
    private transient HandPoseEvidence evidenciaPoseManos;
    private Map<String, SoapEvidence> evidenciaJabon = Collections.emptyMap();
    /** Frame sequence that produced regional soap evidence; never inferred from a later observation. */
    private Long evidenciaJabonSecuencia;
    /** Reloj interno para intervalos; no forma parte del JSON público ni lo aporta el cliente. */
    private long serverReceivedAtMonotonicMs;
    /** Server-owned ingress mark for evidence freshness and step timing. */
    private transient long serverIngressAtMonotonicNanos;
    private transient boolean serverIngressTimestampSet;
    private transient boolean clasificacionResuelta;
    private transient HandwashingStep pasoResuelto;
    private transient OmsAction accionResuelta;
    private transient boolean validacionEvidenciaResuelta;
    private transient String errorEvidenciaMovimiento;

    public DetectionEvent() {}

    public DetectionEvent(String sessionId, String claseDetectada, float confianza, String timestamp) {
        this.sessionId = sessionId;
        this.claseDetectada = claseDetectada;
        this.confianza = confianza;
        this.timestamp = timestamp;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getClaseDetectada() { return claseDetectada; }
    public void setClaseDetectada(String claseDetectada) {
        if (!Objects.equals(this.claseDetectada, claseDetectada)) {
            this.claseDetectada = claseDetectada;
            clasificacionResuelta = false;
            pasoResuelto = null;
            accionResuelta = null;
        }
    }
    public Float getConfianza() { return confianza; }
    public void setConfianza(Float confianza) { this.confianza = confianza; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getProducerEpoch() { return producerEpoch; }
    public void setProducerEpoch(String producerEpoch) { this.producerEpoch = producerEpoch; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getFrameSequence() { return frameSequence; }
    public void setFrameSequence(Long frameSequence) { this.frameSequence = frameSequence; }
    public Long getControlSequence() { return controlSequence; }
    public void setControlSequence(Long controlSequence) { this.controlSequence = controlSequence; }
    public Long getFrameWatermark() { return frameWatermark; }
    public void setFrameWatermark(Long frameWatermark) { this.frameWatermark = frameWatermark; }
    public Integer getPresenceHandsVisible() { return presenceHandsVisible; }
    public void setPresenceHandsVisible(Integer presenceHandsVisible) {
        this.presenceHandsVisible = presenceHandsVisible;
    }
    public Long getCaptureAgeMs() { return captureAgeMs; }
    public void setCaptureAgeMs(Long captureAgeMs) { this.captureAgeMs = captureAgeMs; }
    public MovementEvidence getEvidenciaMovimiento() { return evidenciaMovimiento; }
    public void setEvidenciaMovimiento(MovementEvidence evidenciaMovimiento) {
        this.evidenciaMovimiento = evidenciaMovimiento;
        validacionEvidenciaResuelta = false;
        errorEvidenciaMovimiento = null;
    }
    @JsonIgnore
    public HandPoseEvidence getEvidenciaPoseManos() { return evidenciaPoseManos; }
    @JsonIgnore
    public void setEvidenciaPoseManos(HandPoseEvidence evidenciaPoseManos) {
        this.evidenciaPoseManos = evidenciaPoseManos;
    }
    public Map<String, SoapEvidence> getEvidenciaJabon() { return evidenciaJabon; }
    public void setEvidenciaJabon(Map<String, SoapEvidence> evidenciaJabon) {
        this.evidenciaJabon = evidenciaJabon == null || evidenciaJabon.isEmpty()
            ? Collections.emptyMap() : new LinkedHashMap<>(evidenciaJabon);
    }
    public Long getEvidenciaJabonSecuencia() { return evidenciaJabonSecuencia; }
    public void setEvidenciaJabonSecuencia(Long evidenciaJabonSecuencia) {
        this.evidenciaJabonSecuencia = evidenciaJabonSecuencia;
    }
    @JsonIgnore
    public void setServerIngressAtMonotonicNanos(long timestampNanos) {
        serverIngressAtMonotonicNanos = timestampNanos;
        serverIngressTimestampSet = true;
    }
    /** Uses the server's HTTP-ingress clock for wash intervals, or a local fallback for internal callers. */
    @JsonIgnore
    public long serverIngressAtMonotonicNanosOr(long fallbackNanos) {
        return serverIngressTimestampSet ? serverIngressAtMonotonicNanos : fallbackNanos;
    }
    /**
     * A capture-age claim is combined with time spent inside Java since HTTP
     * ingress. The clocks are never compared across processes; this only
     * prevents lock/queue delay from making an old pose look fresh again.
     */
    @JsonIgnore
    public boolean evidenciaMovimientoReciente() {
        if (evidenciaMovimiento == null || !evidenciaMovimiento.esReciente()) return false;
        if (!serverIngressTimestampSet) return true;
        return evidenciaMovimientoCaducaEnMonotonicNanos() - System.nanoTime() >= 0L;
    }
    @JsonIgnore
    public long evidenciaMovimientoCaducaEnMonotonicNanos() {
        if (evidenciaMovimiento == null || !evidenciaMovimiento.esReciente()) return 0L;
        long baseNanos = serverIngressTimestampSet
            ? serverIngressAtMonotonicNanos : System.nanoTime();
        long remainingFreshnessMs = MovementEvidence.MAX_EDAD_RECIENTE_MS
            - evidenciaMovimiento.antiguedadMs();
        return baseNanos + TimeUnit.MILLISECONDS.toNanos(remainingFreshnessMs);
    }
    @JsonIgnore
    public HandwashingStep getPasoLavadoResuelto() {
        resolverClasificacion();
        return pasoResuelto;
    }
    @JsonIgnore
    public OmsAction getAccionOmsResuelta() {
        resolverClasificacion();
        return accionResuelta;
    }
    @JsonIgnore
    public String getErrorEvidenciaMovimiento() {
        if (!validacionEvidenciaResuelta) {
            errorEvidenciaMovimiento = evidenciaMovimiento == null
                ? null : evidenciaMovimiento.validar();
            validacionEvidenciaResuelta = true;
        }
        return errorEvidenciaMovimiento;
    }
    private void resolverClasificacion() {
        if (clasificacionResuelta) return;
        pasoResuelto = HandwashingStep.fromClaseModelo(claseDetectada);
        accionResuelta = OmsAction.fromClaseModelo(claseDetectada);
        clasificacionResuelta = true;
    }
    @JsonIgnore
    public long getServerReceivedAtMonotonicMs() { return serverReceivedAtMonotonicMs; }
    @JsonIgnore
    public void setServerReceivedAtMonotonicMs(long serverReceivedAtMonotonicMs) {
        this.serverReceivedAtMonotonicMs = serverReceivedAtMonotonicMs;
    }
}
