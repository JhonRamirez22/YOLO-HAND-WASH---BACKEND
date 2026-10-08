package com.handwash.api.v1.mapper;

import com.handwash.api.v1.dto.DetectionRequest;
import com.handwash.api.v1.dto.MovementEvidenceRequest;
import com.handwash.api.v1.dto.SoapEvidenceRequest;
import com.handwash.api.v1.dto.SoapEvidenceState;
import com.handwash.model.SoapEvidenceStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionRequestMapperTest {
    private final DetectionRequestMapper mapper = new DetectionRequestMapper();

    @Test
    void mapsVersionedEvidenceDtosIntoIndependentDomainValues() {
        var request = new DetectionRequest(
            "session", "Paso1_Palmas", 0.91f, "2026-10-02T00:00:00Z",
            "epoch", "DETECTION", 18L, null, null, 25L,
            new MovementEvidenceRequest(18L, 2, 0.2, true, 25L),
            Map.of("PALMA_IZQUIERDA", new SoapEvidenceRequest(
                SoapEvidenceState.ESPUMA_VISIBLE, 0.94f)), 18L, null);

        var event = mapper.toDomain(request);

        assertEquals(18L, event.getEvidenciaMovimiento().secuencia());
        assertEquals(18L, event.getEvidenciaJabonSecuencia());
        assertEquals(2, event.getEvidenciaMovimiento().manosVisibles());
        assertNotSame(request.evidenciaMovimiento(), event.getEvidenciaMovimiento());
        assertEquals(SoapEvidenceStatus.ESPUMA_VISIBLE,
            event.getEvidenciaJabon().get("PALMA_IZQUIERDA").getEstado());
        assertEquals(0.94f, event.getEvidenciaJabon().get("PALMA_IZQUIERDA").getConfianza());
        assertNotSame(request.evidenciaJabon().get("PALMA_IZQUIERDA"),
            event.getEvidenciaJabon().get("PALMA_IZQUIERDA"));
    }

    @Test
    void preservesAbsentOptionalEvidence() {
        var request = new DetectionRequest("session", "Fondo", 0.9f,
            "2026-10-02T00:00:00Z", null, null, null, null, null, null, null, null, null, null);

        var event = mapper.toDomain(request);

        assertNull(event.getEvidenciaMovimiento());
        assertTrue(event.getEvidenciaJabon().isEmpty());
    }

    @Test
    void mapsBilateralPresenceCountForTheDedicatedProducerEvent() {
        var request = new DetectionRequest("session", "PRESENCIA_MANOS", 1.0f,
            "2026-10-02T00:00:00Z", "epoch", "PRESENCE", null, 3L, 12L,
            5L, null, Map.of(), null, 2);

        var event = mapper.toDomain(request);

        assertEquals(2, event.getPresenceHandsVisible());
        assertEquals("PRESENCE", event.getEventType());
        assertNull(event.getFrameSequence());
        assertNull(event.getEvidenciaMovimiento());
    }

    @Test
    void preservesMalformedSoapEvidenceForTheDomainValidatorInsteadOfThrowing() {
        var request = new DetectionRequest("session", "OMS_03_FROTAR_PALMAS", 0.9f,
            "2026-10-02T00:00:00Z", null, null, null, null, null, null, null,
            Map.of("PALMA_IZQUIERDA", new SoapEvidenceRequest(
                SoapEvidenceState.ESPUMA_VISIBLE, null)), 7L, null);

        var event = mapper.toDomain(request);

        assertEquals(SoapEvidenceStatus.ESPUMA_VISIBLE,
            event.getEvidenciaJabon().get("PALMA_IZQUIERDA").getEstado());
        assertNull(event.getEvidenciaJabon().get("PALMA_IZQUIERDA").getConfianza());
    }

    @Test
    void publicRequestDtoDoesNotExposeDomainTypes() {
        assertTrue(java.util.Arrays.stream(DetectionRequest.class.getRecordComponents())
            .noneMatch(component -> component.getType().getPackageName().equals("com.handwash.model")));
    }
}
