package com.handwash.api.v1.dto;

/** Short-lived, single-use credential for the authenticated WebSocket upgrade. */
public record WebSocketTicketResponse(String ticket, String expiresAt) {}
