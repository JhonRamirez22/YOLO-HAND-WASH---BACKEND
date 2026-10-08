package com.handwash.model;

public class Violation {
    private ViolationType tipo;
    private String detalle;
    private String paso;
    private String timestamp;

    public Violation() {}

    public Violation(ViolationType tipo, String detalle) {
        this.tipo = tipo;
        this.detalle = detalle;
    }

    public Violation(ViolationType tipo, String detalle, String paso, String timestamp) {
        this.tipo = tipo;
        this.detalle = detalle;
        this.paso = paso;
        this.timestamp = timestamp;
    }

    public ViolationType getTipo() { return tipo; }
    public void setTipo(ViolationType tipo) { this.tipo = tipo; }
    public String getDetalle() { return detalle; }
    public void setDetalle(String detalle) { this.detalle = detalle; }
    public String getPaso() { return paso; }
    public void setPaso(String paso) { this.paso = paso; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
