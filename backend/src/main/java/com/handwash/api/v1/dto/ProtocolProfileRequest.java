package com.handwash.api.v1.dto;

import java.util.Map;

/** Step codes are canonical wire names (PASO_1_PALMAS ... PASO_6_PUNTA_DE_DEDOS); {@code motivo} is mandatory. */
public record ProtocolProfileRequest(Map<String, Long> tiemposPasoMs, Long umbralLentoMs, String motivo) {}
