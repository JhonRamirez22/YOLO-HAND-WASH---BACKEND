package com.handwash.model;

public class SoapEvidence {
    private SoapEvidenceStatus estado;
    // Null permite que el receptor rechace evidencia sin score explícito.
    private Float confianza;

    public SoapEvidence() {}

    public SoapEvidence(SoapEvidenceStatus estado, float confianza) {
        this.estado = estado;
        this.confianza = confianza;
    }

    public SoapEvidenceStatus getEstado() { return estado; }
    public void setEstado(SoapEvidenceStatus estado) { this.estado = estado; }
    public Float getConfianza() { return confianza; }
    public void setConfianza(Float confianza) { this.confianza = confianza; }
}
