package com.handwash.strategy;

import com.handwash.model.PasoLavado;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreadorProtocoloTest {
    @Test
    void builtInObjectivesEqualTheSumOfAllSevenFrictionPhases() {
        assertTotalMatchesPhases(new CreadorProtocolo.Objetivo40Segundos().crear());
        assertTotalMatchesPhases(new CreadorProtocolo.Objetivo60Segundos().crear());
    }

    @Test
    void rejectsAProtocolWhosePhaseDurationsDisagreeWithItsAdvertisedObjective() {
        CreadorProtocolo inconsistent = new CreadorProtocolo() {
            @Override
            protected ReglaValidacionStrategy crearEstrategia() {
                return new ReglaValidacionStrategy() {
                    @Override public boolean validarTiempoPaso(PasoLavado paso, long actualMs) {
                        return actualMs >= getTiempoRequeridoPaso(paso);
                    }

                    @Override public long getTiempoRequeridoPaso(PasoLavado paso) { return 1_000L; }
                    @Override public long getDuracionTotalMs() { return 60_000L; }
                };
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, inconsistent::crear);
        assertTrue(error.getMessage().contains("no coincide"));
    }

    @Test
    void rejectsAProtocolWithAnUnmeasurableFrictionPhase() {
        CreadorProtocolo missingPhase = new CreadorProtocolo() {
            @Override
            protected ReglaValidacionStrategy crearEstrategia() {
                return new ReglaValidacionStrategy() {
                    @Override public boolean validarTiempoPaso(PasoLavado paso, long actualMs) {
                        return true;
                    }

                    @Override public long getTiempoRequeridoPaso(PasoLavado paso) {
                        return paso == PasoLavado.PASO_4_NUDILLOS ? 0L : 5_000L;
                    }

                    @Override public long getDuracionTotalMs() { return 30_000L; }
                };
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, missingPhase::crear);
        assertTrue(error.getMessage().contains("duración positiva"));
    }

    @Test
    void rejectsOverflowInsteadOfAcceptingAWrappedDurationSum() {
        CreadorProtocolo overflowing = new CreadorProtocolo() {
            @Override
            protected ReglaValidacionStrategy crearEstrategia() {
                return new ReglaValidacionStrategy() {
                    @Override public boolean validarTiempoPaso(PasoLavado paso, long actualMs) {
                        return true;
                    }

                    @Override public long getTiempoRequeridoPaso(PasoLavado paso) {
                        return Long.MAX_VALUE;
                    }

                    @Override public long getDuracionTotalMs() { return Long.MAX_VALUE; }
                };
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, overflowing::crear);
        assertTrue(error.getMessage().contains("desborda"));
    }

    private static void assertTotalMatchesPhases(ReglaValidacionStrategy strategy) {
        long sum = 0L;
        for (PasoLavado paso : PasoLavado.values()) {
            if (paso.getNumero() >= 1 && paso.getNumero() <= 7) {
                assertTrue(strategy.getTiempoRequeridoPaso(paso) > 0L);
                sum += strategy.getTiempoRequeridoPaso(paso);
            }
        }
        assertEquals(strategy.getDuracionTotalMs(), sum);
    }
}
