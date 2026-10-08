package com.handwash.model;

public class Infraccion {
    private TipoInfraccion tipo;
    private String detalle;
    private String paso;
    private String timestamp;

    public Infraccion() {}

    public Infraccion(TipoInfraccion tipo, String detalle) {
        this.tipo = tipo;
        this.detalle = detalle;
    }

    public Infraccion(TipoInfraccion tipo, String detalle, String paso, String timestamp) {
        this.tipo = tipo;
        this.detalle = detalle;
        this.paso = paso;
        this.timestamp = timestamp;
    }

    public TipoInfraccion getTipo() { return tipo; }
    public void setTipo(TipoInfraccion tipo) { this.tipo = tipo; }
    public String getDetalle() { return detalle; }
    public void setDetalle(String detalle) { this.detalle = detalle; }
    public String getPaso() { return paso; }
    public void setPaso(String paso) { this.paso = paso; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
