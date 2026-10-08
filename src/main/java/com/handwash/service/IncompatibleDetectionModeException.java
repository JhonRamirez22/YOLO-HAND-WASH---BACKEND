package com.handwash.service;

public class IncompatibleDetectionModeException extends RuntimeException {
    public IncompatibleDetectionModeException(String mode) {
        super("La sesión ya está fijada en el modo " + mode + "; crea otra sesión para cambiar el modelo");
    }

    public IncompatibleDetectionModeException(String mode, String reason) {
        super(reason + " (modo solicitado: " + mode + ")");
    }
}
