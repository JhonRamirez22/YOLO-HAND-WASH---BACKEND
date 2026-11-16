package com.handwash.strategy;

import com.handwash.model.WashStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolCreatorTest {
    @Test
    void builtInObjectivesEqualTheSumOfAllSevenFrictionPhases() {
        assertTotalMatchesPhases(new ProtocolCreator.Objetivo40Segundos().crear());
        assertTotalMatchesPhases(new ProtocolCreator.Objetivo60Segundos().crear());
    }

    @Test
    void rejectsAProtocolWhosePhaseDurationsDisagreeWithItsAdvertisedObjective() {
        ProtocolCreator inconsistent = new ProtocolCreator() {
            @Override
            protected ValidationRuleStrategy crearEstrategia() {
                return new ValidationRuleStrategy() {
                    @Override public boolean validarTiempoPaso(WashStep paso, long actualMs) {
                        return actualMs >= getTiempoRequeridoPaso(paso);
                    }

                    @Override public long getTiempoRequeridoPaso(WashStep paso) { return 1_000L; }
                    @Override public long getDuracionTotalMs() { return 60_000L; }
                };
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, inconsistent::crear);
        assertTrue(error.getMessage().contains("no coincide"));
    }

    @Test
    void rejectsAProtocolWithAnUnmeasurableFrictionPhase() {
        ProtocolCreator missingPhase = new ProtocolCreator() {
            @Override
            protected ValidationRuleStrategy crearEstrategia() {
                return new ValidationRuleStrategy() {
                    @Override public boolean validarTiempoPaso(WashStep paso, long actualMs) {
                        return true;
                    }

                    @Override public long getTiempoRequeridoPaso(WashStep paso) {
                        return paso == WashStep.PASO_4_NUDILLOS ? 0L : 5_000L;
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
        ProtocolCreator overflowing = new ProtocolCreator() {
            @Override
            protected ValidationRuleStrategy crearEstrategia() {
                return new ValidationRuleStrategy() {
                    @Override public boolean validarTiempoPaso(WashStep paso, long actualMs) {
                        return true;
                    }

                    @Override public long getTiempoRequeridoPaso(WashStep paso) {
                        return Long.MAX_VALUE;
                    }

                    @Override public long getDuracionTotalMs() { return Long.MAX_VALUE; }
                };
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, overflowing::crear);
        assertTrue(error.getMessage().contains("desborda"));
    }

    private static void assertTotalMatchesPhases(ValidationRuleStrategy strategy) {
        long sum = 0L;
        for (WashStep paso : WashStep.values()) {
            if (paso.getNumero() >= 1 && paso.getNumero() <= 7) {
                assertTrue(strategy.getTiempoRequeridoPaso(paso) > 0L);
                sum += strategy.getTiempoRequeridoPaso(paso);
            }
        }
        assertEquals(strategy.getDuracionTotalMs(), sum);
    }
}
