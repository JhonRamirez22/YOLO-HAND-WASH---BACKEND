package com.handwash.api.v1.dto;

/** Stable v1 representation of a sequence or evidence violation. */
public record ViolationResponse(String tipo, String detalle, String paso, String timestamp) {}
