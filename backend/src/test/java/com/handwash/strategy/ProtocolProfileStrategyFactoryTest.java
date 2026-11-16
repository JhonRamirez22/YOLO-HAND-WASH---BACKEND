package com.handwash.strategy;

import com.handwash.decorator.strategy.MetricsWashStrategyDecoratorFactory;
import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;
import com.handwash.model.ProtocolType;
import com.handwash.service.HandwashMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolProfileStrategyFactoryTest {
    @Test
    void soapAndWaterRequiresTheBalancedPolicy() {
        ValidationRuleStrategyFactory factory = factory(false, ProtocolProfileProvider.predeterminado());

        assertFalse(factory.isDisponible(ProtocolType.AGUA_Y_JABON));
        assertFalse(factory.protocolosDisponibles().contains(ProtocolType.AGUA_Y_JABON));
        assertThrows(IllegalArgumentException.class, () -> factory.crear(ProtocolType.AGUA_Y_JABON));
    }

    @Test
    void buildsSixMovementV2RuleFromTheProfileInForce() {
        ValidationRuleStrategy strategy = factory(true, ProtocolProfileProvider.predeterminado())
            .crear(ProtocolType.AGUA_Y_JABON);

        assertEquals("FRICCION_BALANCEADA_V2", strategy.getVersionPolitica());
        assertEquals(ProtocolProfile.PASOS, strategy.getPasosAcreditables());
        assertEquals(42_000L, strategy.getDuracionMinimaFriccionMs());
        assertEquals(42_000L, strategy.getDuracionTotalMs());
        assertEquals(7_000L, strategy.getTiempoRequeridoPaso(WashStep.PASO_5_PULGAR));
        assertEquals(0L, strategy.getTiempoRequeridoPaso(WashStep.PASO_7_CIRCULARES));
        assertFalse(strategy.validarTiempoPaso(WashStep.PASO_1_PALMAS, 6_999L));
        assertTrue(strategy.validarTiempoPaso(WashStep.PASO_1_PALMAS, 7_000L));
        assertEquals(1, strategy.getVersionPerfilProtocolo());
        assertEquals(60_000L, strategy.getUmbralLavadoLentoMs());
    }

    @Test
    void eachSessionCapturesTheVersionInForceAtCreation() {
        Map<WashStep, Long> tiempos = new EnumMap<>(ProtocolProfile.uniforme(8_000L));
        ProtocolProfile v2 = ProtocolProfile.PREDETERMINADO.siguienteVersion(tiempos, 70_000L, "admin", 5L);
        ProtocolProfile[] vigente = {ProtocolProfile.PREDETERMINADO};
        ValidationRuleStrategyFactory factory = factory(true, () -> vigente[0]);

        ValidationRuleStrategy antes = factory.crear(ProtocolType.AGUA_Y_JABON);
        vigente[0] = v2;
        ValidationRuleStrategy despues = factory.crear(ProtocolType.AGUA_Y_JABON);

        assertEquals(1, antes.getVersionPerfilProtocolo());
        assertEquals(7_000L, antes.getTiempoRequeridoPaso(WashStep.PASO_2_DORSOS));
        assertEquals(2, despues.getVersionPerfilProtocolo());
        assertEquals(48_000L, despues.getDuracionTotalMs());
    }

    @Test
    void legacyProtocolsKeepTheirFixedRulesAndHaveNoProfileVersion() {
        ValidationRuleStrategy legacy = factory(false, ProtocolProfileProvider.predeterminado())
            .crear(ProtocolType.DOMESTICO);

        assertEquals("LEGACY_V1", legacy.getVersionPolitica());
        assertEquals(40_000L, legacy.getDuracionTotalMs());
        assertNull(legacy.getVersionPerfilProtocolo());
    }

    private static ValidationRuleStrategyFactory factory(boolean balanced, ProtocolProfileProvider perfiles) {
        ValidationRuleStrategyFactory factory = new ValidationRuleStrategyFactory(
            new MetricsWashStrategyDecoratorFactory(HandwashMetrics.noop()), perfiles);
        ReflectionTestUtils.setField(factory, "balancedFrictionEnabled", balanced);
        return factory;
    }
}
