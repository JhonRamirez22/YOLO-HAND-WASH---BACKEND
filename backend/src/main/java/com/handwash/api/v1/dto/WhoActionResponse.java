package com.handwash.api.v1.dto;

/** Reference action in the public soap-and-water protocol catalog. */
public record WhoActionResponse(
    int orden,
    String codigo,
    String nombre,
    String instruccion,
    String claseModeloRequerida
) {}
