package com.handwash.api.v1.dto;

/** Pairing-code input for the producer or read-only dashboard; no human account system exists. */
public record LoginRequest(String code, String producerProtocolVersion) {}
