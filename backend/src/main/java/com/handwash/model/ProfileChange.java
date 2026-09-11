package com.handwash.model;

/** Audit view of one profile version: who changed it, why, and whether it is in force. */
public record ProfileChange(ProtocolProfile perfil, String motivo, Integer revertidoDe, String estado,
                           String decididoPor, Long decididoEnEpochMs) {
    public static final String ACTIVA = "ACTIVE";
    public static final String PENDIENTE = "PENDING";
    public static final String RECHAZADA = "REJECTED";
}
