package com.handwash.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AccionOmsClinicalContractTest {
    private static final List<String> WHO_SOAP_AND_WATER_ORDER = List.of(
        "OMS_01_MOJAR_MANOS",
        "OMS_02_APLICAR_JABON",
        "OMS_03_FROTAR_PALMAS",
        "OMS_04_FROTAR_DORSOS",
        "OMS_05_FROTAR_ENTRE_DEDOS",
        "OMS_06_FROTAR_DORSO_DE_DEDOS",
        "OMS_07_FROTAR_PULGARES",
        "OMS_08_FROTAR_PUNTAS_DE_DEDOS",
        "OMS_09_ENJUAGAR_MANOS",
        "OMS_10_SECAR_TOALLA_DESECHABLE",
        "OMS_11_CERRAR_GRIFO_CON_TOALLA"
    );

    @Test
    void keepsTheOfficialSoapAndWaterActionsInCanonicalOrder() {
        List<AccionOms> sequence = AccionOms.SECUENCIA;

        assertEquals(WHO_SOAP_AND_WATER_ORDER,
            sequence.stream().map(AccionOms::getClaseModelo).toList());
        for (int index = 0; index < sequence.size() - 1; index++) {
            assertSame(sequence.get(index + 1), sequence.get(index).siguiente());
        }
        assertNull(sequence.get(sequence.size() - 1).siguiente());
        assertFalse(sequence.contains(AccionOms.CONTACTO_RIESGO));
        assertFalse(sequence.contains(AccionOms.SIN_EVIDENCIA));
        assertNull(AccionOms.CONTACTO_RIESGO.siguiente());
        assertNull(AccionOms.SIN_EVIDENCIA.siguiente());
    }

    @Test
    void mapsExactlyTwoHandRegionsToEachOfTheSixRubbingActions() {
        Map<AccionOms, Integer> expectedRegionsPerAction = Map.of(
            AccionOms.FROTAR_PALMAS, 2,
            AccionOms.FROTAR_DORSOS, 2,
            AccionOms.FROTAR_ENTRE_DEDOS, 2,
            AccionOms.FROTAR_DORSO_DE_DEDOS, 2,
            AccionOms.FROTAR_PULGARES, 2,
            AccionOms.FROTAR_PUNTAS_DE_DEDOS, 2
        );
        Map<AccionOms, Integer> actualRegionsPerAction = new EnumMap<>(AccionOms.class);
        for (RegionJabon region : RegionJabon.values()) {
            actualRegionsPerAction.merge(region.getAccionVerificacion(), 1, Integer::sum);
        }

        assertEquals(12, RegionJabon.values().length);
        assertEquals(expectedRegionsPerAction, actualRegionsPerAction);
    }
}
