package com.handwash.model;

public class EvidenciaJabon {
    private EstadoEvidenciaJabon estado;
    // Null permite que el receptor rechace evidencia sin score explícito.
    private Float confianza;

    public EvidenciaJabon() {}

    public EvidenciaJabon(EstadoEvidenciaJabon estado, float confianza) {
        this.estado = estado;
        this.confianza = confianza;
    }

    public EstadoEvidenciaJabon getEstado() { return estado; }
    public void setEstado(EstadoEvidenciaJabon estado) { this.estado = estado; }
    public Float getConfianza() { return confianza; }
    public void setConfianza(Float confianza) { this.confianza = confianza; }
}
