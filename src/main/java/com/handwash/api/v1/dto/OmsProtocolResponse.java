package com.handwash.api.v1.dto;

import java.util.List;

/** Reference catalog; its DTO does not claim that the active model observes every phase. */
public record OmsProtocolResponse(
    String metodo,
    int duracionReferenciaMinMs,
    int duracionReferenciaMaxMs,
    List<OmsActionResponse> acciones,
    String nota,
    String notaDuraciones,
    String fuente
) {
    public OmsProtocolResponse {
        acciones = List.copyOf(acciones);
    }
}
