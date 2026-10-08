package com.handwash.service;

public class ModoDeteccionIncompatibleException extends RuntimeException {
    public ModoDeteccionIncompatibleException(String mode) {
        super("La sesión ya está fijada en el modo " + mode + "; crea otra sesión para cambiar el modelo");
    }

    public ModoDeteccionIncompatibleException(String mode, String reason) {
        super(reason + " (modo solicitado: " + mode + ")");
    }
}
