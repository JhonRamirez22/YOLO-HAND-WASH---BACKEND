package com.handwash.strategy;

import com.handwash.model.PasoLavado;

/** Factory Method: cada creador concreto decide la estrategia del protocolo. */
public abstract class CreadorProtocolo {
    public final ReglaValidacionStrategy crear() {
        ReglaValidacionStrategy estrategia = crearEstrategia();
        if (estrategia == null || estrategia.getDuracionTotalMs() <= 0)
            throw new IllegalStateException("El protocolo debe tener una duración positiva");
        validarDistribucionDeDuracion(estrategia);
        return estrategia;
    }

    private static void validarDistribucionDeDuracion(ReglaValidacionStrategy estrategia) {
        boolean[] fasesEncontradas = new boolean[8];
        int cantidadFases = 0;
        long sumaFasesMs = 0L;
        for (PasoLavado paso : PasoLavado.values()) {
            int numero = paso.getNumero();
            if (numero < 1 || numero > 7) continue;
            if (fasesEncontradas[numero]) {
                throw new IllegalStateException("La estrategia repite el número de fase " + numero);
            }
            fasesEncontradas[numero] = true;
            cantidadFases++;
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
        if (cantidadFases != 7) {
            throw new IllegalStateException(
                "La estrategia debe definir exactamente las siete fases de fricción");
        }
        if (sumaFasesMs != estrategia.getDuracionTotalMs()) {
            throw new IllegalStateException(
                "La suma de fases (" + sumaFasesMs + " ms) no coincide con el objetivo total ("
                    + estrategia.getDuracionTotalMs() + " ms)");
        }
    }

    protected abstract ReglaValidacionStrategy crearEstrategia();

    public static final class Objetivo60Segundos extends CreadorProtocolo {
        @Override protected ReglaValidacionStrategy crearEstrategia() {
            return new ClinicoQuirurgicoStrategy();
        }
    }

    public static final class Objetivo40Segundos extends CreadorProtocolo {
        @Override protected ReglaValidacionStrategy crearEstrategia() {
            return new DomesticoStrategy();
        }
    }
}
