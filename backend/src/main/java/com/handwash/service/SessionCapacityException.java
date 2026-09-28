package com.handwash.service;

public class SessionCapacityException extends RuntimeException {
    public SessionCapacityException() {
        super("Se alcanzó el límite de sesiones en memoria");
    }
}
