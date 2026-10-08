package com.handwash.model;

public class Progress {
    private int pasosCompletados;
    private int pasosTotales;

    public Progress() {}

    public Progress(int pasosCompletados, int pasosTotales) {
        this.pasosCompletados = pasosCompletados;
        this.pasosTotales = pasosTotales;
    }

    public int getPasosCompletados() { return pasosCompletados; }
    public void setPasosCompletados(int pasosCompletados) { this.pasosCompletados = pasosCompletados; }
    public int getPasosTotales() { return pasosTotales; }
    public void setPasosTotales(int pasosTotales) { this.pasosTotales = pasosTotales; }
}
