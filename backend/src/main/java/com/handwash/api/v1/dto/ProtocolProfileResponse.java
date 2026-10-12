package com.handwash.api.v1.dto;

import com.handwash.model.ProtocolProfile;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProtocolProfileResponse(int version, String protocolo, Map<String, Long> tiemposPasoMs,
                                      long duracionTotalMs, long umbralLentoMs, String origenTiempos,
                                      String creadoPor, long creadoEnEpochMs) {
    public static ProtocolProfileResponse of(ProtocolProfile perfil) {
        Map<String, Long> tiempos = new LinkedHashMap<>();
        ProtocolProfile.PASOS.forEach(paso -> tiempos.put(paso.name(), perfil.tiemposPasoMs().get(paso)));
        return new ProtocolProfileResponse(perfil.version(), "AGUA_Y_JABON", tiempos,
            perfil.duracionTotalMs(), perfil.umbralLentoMs(), "REGLA_DEL_PROYECTO_NO_UMBRAL_OMS",
            perfil.creadoPor(), perfil.creadoEnEpochMs());
    }
}
