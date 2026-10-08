package com.handwash.model;

public enum ViolationType {
    PASO_OMITIDO("Paso Omitido"),
    PASO_INVALIDO("Paso Invalido"),
    TIEMPO_INSUFICIENTE("Tiempo Insuficiente"),
    ERROR_PROCESAMIENTO("Error de Procesamiento"),
    ACCION_OMS_FUERA_DE_SECUENCIA("Acción OMS fuera de secuencia"),
    FASE_OMS_DEMASIADO_CORTA("Fase OMS sin evidencia temporal suficiente"),
    COBERTURA_JABON_INCOMPLETA("Cobertura visible de jabón incompleta"),
    CONTACTO_RIESGO("Contacto visible de riesgo de contaminación"),
    EVIDENCIA_VISUAL_INTERRUPTA("Manos o acción fuera de observación continua"),
    MODO_DETECCION_INCOMPATIBLE("Modo de detección incompatible"),
    DURACION_OMS_INSUFICIENTE("Duración total observada insuficiente");

    private final String descripcion;

    ViolationType(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getDescripcion() { return descripcion; }
}
