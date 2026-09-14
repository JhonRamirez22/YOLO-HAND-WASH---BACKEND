package com.handwash.model;

public class StepDetail {
    private String paso;
    private long tiempoAcumuladoMs;
    private long tiempoRequeridoMs;
    private boolean completado;

    public StepDetail() {}

    public StepDetail(String paso, long tiempoAcumuladoMs, long tiempoRequeridoMs, boolean completado) {
        this.paso = paso;
        this.tiempoAcumuladoMs = tiempoAcumuladoMs;
        this.tiempoRequeridoMs = tiempoRequeridoMs;
        this.completado = completado;
    }

    public String getPaso() { return paso; }
    public void setPaso(String paso) { this.paso = paso; }
    public long getTiempoAcumuladoMs() { return tiempoAcumuladoMs; }
    public void setTiempoAcumuladoMs(long tiempoAcumuladoMs) { this.tiempoAcumuladoMs = tiempoAcumuladoMs; }
    public long getTiempoRequeridoMs() { return tiempoRequeridoMs; }
    public void setTiempoRequeridoMs(long tiempoRequeridoMs) { this.tiempoRequeridoMs = tiempoRequeridoMs; }
    public boolean isCompletado() { return completado; }
    public void setCompletado(boolean completado) { this.completado = completado; }
}
