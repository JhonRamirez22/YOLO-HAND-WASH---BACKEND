package com.handwash.strategy;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;

import java.util.List;
import java.util.Objects;

/** Soap-and-water friction rule built from one immutable profile version. */
public final class ProtocolProfileStrategy implements ValidationRuleStrategy {
    private final ProtocolProfile perfil;

    public ProtocolProfileStrategy(ProtocolProfile perfil) {
        this.perfil = Objects.requireNonNull(perfil);
    }

    @Override public List<WashStep> getPasosAcreditables() { return ProtocolProfile.PASOS; }

    @Override public boolean validarTiempoPaso(WashStep paso, long tiempoAcumuladoMs) {
        long requerido = getTiempoRequeridoPaso(paso);
        return requerido > 0 && tiempoAcumuladoMs >= requerido;
    }

    @Override public long getTiempoRequeridoPaso(WashStep paso) {
        return perfil.tiemposPasoMs().getOrDefault(paso, 0L);
    }

    @Override public long getDuracionTotalMs() { return perfil.duracionTotalMs(); }

    @Override public Integer getVersionPerfilProtocolo() { return perfil.version(); }

    @Override public long getUmbralLavadoLentoMs() { return perfil.umbralLentoMs(); }
}
