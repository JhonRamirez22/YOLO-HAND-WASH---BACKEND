package com.handwash.model;

import com.handwash.recognition.GeometryShadowSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WashStateResponse {
    private FrictionEvaluation evaluacionFriccion;
    public FrictionEvaluation getEvaluacionFriccion() { return evaluacionFriccion; }
    public void setEvaluacionFriccion(FrictionEvaluation value) { evaluacionFriccion = value; }
    private String sessionId;
    private String estadoIntencion = "NO_EVALUADA";
    private String motivoIntencion;
    private long tiempoConfirmacionIntencionMs;
    private long umbralConfirmacionIntencionMs;
    private int manosVisibles;
    private String claseCandidata;
    private Float confianzaCandidata;
    private String estadoActual;
    private long tiempoAcumuladoMs;
    private Infraction infraccion;
    private Progress progreso;
    private Boolean manoDetectada;
    private Float confianzaDeteccion;
    /** Campo heredado del cliente móvil, conservado para compatibilidad. */
    private Float manoConfianza;
    private String estadoSesion;
    private String messageType;
    private List<Infraction> historialInfracciones = new ArrayList<>();
    private int intentosReiniciados;
    private Infraction ultimoErrorReinicio;
    private String modoEvaluacion;
    private Map<String, String> coberturaJabon = new LinkedHashMap<>();
    private boolean coberturaJabonCompleta;
    private boolean procedimientoCompletoValidado;
    private boolean secuenciaFriccionObservadaCompleta;
    private long tiempoTotalActivoMs;
    private long duracionMinimaObjetivoMs;
    private GeometryShadowSnapshot geometryShadow;
    private com.handwash.recognition.ShadowSequenceSnapshot decodificadorSombra;

    public WashStateResponse() {}

    public String getEstadoIntencion() { return estadoIntencion; }
    public void setEstadoIntencion(String estadoIntencion) { this.estadoIntencion = estadoIntencion; }
    public String getMotivoIntencion() { return motivoIntencion; }
    public void setMotivoIntencion(String motivoIntencion) { this.motivoIntencion = motivoIntencion; }
    public long getTiempoConfirmacionIntencionMs() { return tiempoConfirmacionIntencionMs; }
    public void setTiempoConfirmacionIntencionMs(long tiempo) { this.tiempoConfirmacionIntencionMs = tiempo; }
    public long getUmbralConfirmacionIntencionMs() { return umbralConfirmacionIntencionMs; }
    public void setUmbralConfirmacionIntencionMs(long tiempo) { this.umbralConfirmacionIntencionMs = tiempo; }
    public int getManosVisibles() { return manosVisibles; }
    public void setManosVisibles(int manosVisibles) { this.manosVisibles = manosVisibles; }
    public String getClaseCandidata() { return claseCandidata; }
    public void setClaseCandidata(String claseCandidata) { this.claseCandidata = claseCandidata; }
    public Float getConfianzaCandidata() { return confianzaCandidata; }
    public void setConfianzaCandidata(Float confianzaCandidata) { this.confianzaCandidata = confianzaCandidata; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getEstadoActual() { return estadoActual; }
    public void setEstadoActual(String estadoActual) { this.estadoActual = estadoActual; }
    public long getTiempoAcumuladoMs() { return tiempoAcumuladoMs; }
    public void setTiempoAcumuladoMs(long tiempoAcumuladoMs) { this.tiempoAcumuladoMs = tiempoAcumuladoMs; }
    public Infraction getInfraccion() { return infraccion; }
    public void setInfraccion(Infraction infraccion) { this.infraccion = infraccion; }
    public Progress getProgreso() { return progreso; }
    public void setProgreso(Progress progreso) { this.progreso = progreso; }
    public Boolean getManoDetectada() { return manoDetectada; }
    public void setManoDetectada(Boolean manoDetectada) { this.manoDetectada = manoDetectada; }
    public Float getConfianzaDeteccion() { return confianzaDeteccion; }
    public void setConfianzaDeteccion(Float confianzaDeteccion) { this.confianzaDeteccion = confianzaDeteccion; }
    public Float getManoConfianza() { return manoConfianza; }
    public void setManoConfianza(Float manoConfianza) { this.manoConfianza = manoConfianza; }
    public String getEstadoSesion() { return estadoSesion; }
    public void setEstadoSesion(String estadoSesion) { this.estadoSesion = estadoSesion; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public List<Infraction> getHistorialInfracciones() { return historialInfracciones; }
    public void setHistorialInfracciones(List<Infraction> historialInfracciones) { this.historialInfracciones = historialInfracciones; }
    public int getIntentosReiniciados() { return intentosReiniciados; }
    public void setIntentosReiniciados(int intentosReiniciados) { this.intentosReiniciados = intentosReiniciados; }
    public Infraction getUltimoErrorReinicio() { return ultimoErrorReinicio; }
    public void setUltimoErrorReinicio(Infraction ultimoErrorReinicio) { this.ultimoErrorReinicio = ultimoErrorReinicio; }
    public String getModoEvaluacion() { return modoEvaluacion; }
    public void setModoEvaluacion(String modoEvaluacion) { this.modoEvaluacion = modoEvaluacion; }
    public Map<String, String> getCoberturaJabon() { return coberturaJabon; }
    public void setCoberturaJabon(Map<String, String> coberturaJabon) {
        this.coberturaJabon = coberturaJabon == null ? new LinkedHashMap<>() : new LinkedHashMap<>(coberturaJabon);
    }
    public boolean isCoberturaJabonCompleta() { return coberturaJabonCompleta; }
    public void setCoberturaJabonCompleta(boolean coberturaJabonCompleta) { this.coberturaJabonCompleta = coberturaJabonCompleta; }
    public boolean isProcedimientoCompletoValidado() { return procedimientoCompletoValidado; }
    public void setProcedimientoCompletoValidado(boolean procedimientoCompletoValidado) { this.procedimientoCompletoValidado = procedimientoCompletoValidado; }
    public boolean isSecuenciaFriccionObservadaCompleta() { return secuenciaFriccionObservadaCompleta; }
    public void setSecuenciaFriccionObservadaCompleta(boolean completa) { this.secuenciaFriccionObservadaCompleta = completa; }
    public long getTiempoTotalActivoMs() { return tiempoTotalActivoMs; }
    public void setTiempoTotalActivoMs(long tiempoTotalActivoMs) { this.tiempoTotalActivoMs = tiempoTotalActivoMs; }
    public long getDuracionMinimaObjetivoMs() { return duracionMinimaObjetivoMs; }
    public void setDuracionMinimaObjetivoMs(long duracionMinimaObjetivoMs) { this.duracionMinimaObjetivoMs = duracionMinimaObjetivoMs; }
    public GeometryShadowSnapshot getGeometryShadow() { return geometryShadow; }
    public void setGeometryShadow(GeometryShadowSnapshot geometryShadow) { this.geometryShadow = geometryShadow; }
    /** Shadow decoder diagnostics; never an accredited step decision. */
    public com.handwash.recognition.ShadowSequenceSnapshot getDecodificadorSombra() { return decodificadorSombra; }
    public void setDecodificadorSombra(com.handwash.recognition.ShadowSequenceSnapshot decodificadorSombra) {
        this.decodificadorSombra = decodificadorSombra;
    }
}
