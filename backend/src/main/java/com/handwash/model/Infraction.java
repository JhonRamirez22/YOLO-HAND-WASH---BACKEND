package com.handwash.model;

public class Infraction {
    private InfractionType tipo;
    private String detalle;
    private String paso;
    private String timestamp;

    public Infraction() {}

    public Infraction(InfractionType tipo, String detalle) {
        this.tipo = tipo;
        this.detalle = detalle;
    }

    public Infraction(InfractionType tipo, String detalle, String paso, String timestamp) {
        this.tipo = tipo;
        this.detalle = detalle;
        this.paso = paso;
        this.timestamp = timestamp;
    }

    public InfractionType getTipo() { return tipo; }
    public void setTipo(InfractionType tipo) { this.tipo = tipo; }
    public String getDetalle() { return detalle; }
    public void setDetalle(String detalle) { this.detalle = detalle; }
    public String getPaso() { return paso; }
    public void setPaso(String paso) { this.paso = paso; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
