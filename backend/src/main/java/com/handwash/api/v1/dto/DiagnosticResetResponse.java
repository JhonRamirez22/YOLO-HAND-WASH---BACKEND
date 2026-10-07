package com.handwash.api.v1.dto;

public record DiagnosticResetResponse(long sequence, long epochMillis, String reason,
                                      String previousState, String nextState) {}
