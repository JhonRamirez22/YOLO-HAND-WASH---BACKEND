package com.handwash.strategy;

import com.handwash.model.WashStep;

/** Factory Method: cada creador concreto decide la estrategia del protocolo. */
public abstract class ProtocolCreator {
    public final ValidationRuleStrategy crear() {
        ValidationRuleStrategy estrategia = crearEstrategia();
        if (estrategia == null || estrategia.getDuracionTotalMs() <= 0)
            throw new IllegalStateException("El protocolo debe tener una duración positiva");
        validarDistribucionDeDuracion(estrategia);
        return estrategia;
    }

    private static void validarDistribucionDeDuracion(ValidationRuleStrategy estrategia) {
        java.util.List<WashStep> pasos = estrategia.getPasosAcreditables();
        if (pasos == null || pasos.isEmpty()) {
            throw new IllegalStateException("La estrategia debe declarar sus fases acreditables");
        }
        boolean[] fasesEncontradas = new boolean[8];
        long sumaFasesMs = 0L;
        for (WashStep paso : pasos) {
            int numero = paso.getNumero();
            if (numero < 1 || numero > 7) {
                throw new IllegalStateException("Fase no acreditable en la estrategia: " + paso.name());
            }
            if (fasesEncontradas[numero]) {
                throw new IllegalStateException("La estrategia repite el número de fase " + numero);
            }
            fasesEncontradas[numero] = true;
            long duracionFaseMs = estrategia.getTiempoRequeridoPaso(paso);
            if (duracionFaseMs <= 0L) {
                throw new IllegalStateException(
                    "La estrategia requiere una duración positiva para " + paso.name());
            }
            try {
                sumaFasesMs = Math.addExact(sumaFasesMs, duracionFaseMs);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("La suma de duraciones del protocolo desborda", overflow);
            }
        }
        if (sumaFasesMs != estrategia.getDuracionTotalMs()) {
            throw new IllegalStateException(
                "La suma de fases (" + sumaFasesMs + " ms) no coincide con el objetivo total ("
                    + estrategia.getDuracionTotalMs() + " ms)");
        }
    }

    protected abstract ValidationRuleStrategy crearEstrategia();

    public static final class Objetivo60Segundos extends ProtocolCreator {
        @Override protected ValidationRuleStrategy crearEstrategia() {
            return new SurgicalClinicalStrategy();
        }
    }

    public static final class Objetivo40Segundos extends ProtocolCreator {
        @Override protected ValidationRuleStrategy crearEstrategia() {
            return new DomesticStrategy();
        }
    }

    /** Soap and water: six movements whose times come from the ADMIN profile in force. */
    public static final class PerfilAguaYJabon extends ProtocolCreator {
        private final ProtocolProfileProvider perfiles;

        public PerfilAguaYJabon(ProtocolProfileProvider perfiles) {
            this.perfiles = java.util.Objects.requireNonNull(perfiles);
        }

        @Override protected ValidationRuleStrategy crearEstrategia() {
            return new ProtocolProfileStrategy(perfiles.perfilVigente());
        }
    }
}
