package com.handwash.strategy;

import com.handwash.model.WashStep;
import java.util.List;

/** Six observable movements; the procedure reference is not a friction timer. */
public final class BalancedFrictionStrategy implements ValidationRuleStrategy {
    public static final String VERSION = "FRICCION_BALANCEADA_V2";
    public static final List<WashStep> STEPS = List.of(
        WashStep.PASO_1_PALMAS, WashStep.PASO_2_DORSOS,
        WashStep.PASO_3_INTERDIGITALES, WashStep.PASO_4_NUDILLOS,
        WashStep.PASO_5_PULGAR, WashStep.PASO_6_PUNTA_DE_DEDOS);
    private final ValidationRuleStrategy delegate;

    public BalancedFrictionStrategy(ValidationRuleStrategy delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        if (STEPS.stream().anyMatch(p -> delegate.getTiempoRequeridoPaso(p) <= 0))
            throw new IllegalArgumentException("Los seis movimientos requieren duración positiva");
    }
    @Override public String getVersionPolitica() { return VERSION; }
    @Override public List<WashStep> getPasosAcreditables() { return STEPS; }
    @Override public long getTiempoRequeridoPaso(WashStep paso) {
        return STEPS.contains(paso) ? delegate.getTiempoRequeridoPaso(paso) : 0;
    }
    @Override public boolean validarTiempoPaso(WashStep paso, long tiempo) {
        return STEPS.contains(paso) && tiempo >= getTiempoRequeridoPaso(paso);
    }
    @Override public long getDuracionTotalMs() { return delegate.getDuracionTotalMs(); }
    @Override public Integer getVersionPerfilProtocolo() { return delegate.getVersionPerfilProtocolo(); }
    @Override public long getUmbralLavadoLentoMs() { return delegate.getUmbralLavadoLentoMs(); }
}
