package com.handwash.api.v1.dto;

import java.util.List;

/** Reference catalog; its DTO does not claim that the active model observes every phase. */
public record WhoProtocolResponse(
    String metodo,
    int duracionReferenciaMinMs,
    int duracionReferenciaMaxMs,
    List<WhoActionResponse> acciones,
    String nota,
    String notaDuraciones,
    String fuente
) {
    public WhoProtocolResponse {
        acciones = List.copyOf(acciones);
    }
}
