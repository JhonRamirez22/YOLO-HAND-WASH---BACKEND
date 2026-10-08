package com.handwash.api.v1.mapper;

import com.handwash.api.v1.dto.SessionDetailsResponse;
import com.handwash.api.v1.dto.FailedAttemptsResponse;
import com.handwash.model.EstadoLavadoResponse;
import com.handwash.model.Infraccion;
import com.handwash.model.IntentoLavadoResumen;
import com.handwash.model.Progreso;
import com.handwash.model.TipoInfraccion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionResponseMapperTest {
    private final SessionResponseMapper mapper = new SessionResponseMapper();

    @Test
    void mapsDomainSnapshotToV1DtosWithoutChangingPublicFieldNamesOrValues() {
        EstadoLavadoResponse domain = new EstadoLavadoResponse();
        domain.setSessionId("session");
        domain.setEstadoIntencion("CONFIRMADA");
        domain.setMotivoIntencion("dos manos visibles");
        domain.setTiempoConfirmacionIntencionMs(650);
        domain.setUmbralConfirmacionIntencionMs(650);
        domain.setManosVisibles(2);
        domain.setEstadoActual("PASO_1_PALMAS");
        domain.setTiempoAcumuladoMs(1250);
        domain.setInfraccion(new Infraccion(TipoInfraccion.PASO_OMITIDO,
            "secuencia incorrecta", "PASO_2_DORSOS", "2026-10-02T00:00:00Z"));
        domain.setProgreso(new Progreso(1, 7));
        domain.setHistorialInfracciones(List.of(domain.getInfraccion()));
        domain.setCoberturaJabon(Map.of("PALMA_IZQUIERDA", "ESPUMA_VISIBLE"));

        var dto = mapper.toEvaluation(domain);
        var details = new SessionDetailsResponse("session", "DOMESTICO", "EN_PROGRESO",
            2500, dto, null);

        assertEquals("session", details.evaluacion().sessionId());
        assertEquals("CONFIRMADA", details.evaluacion().estadoIntencion());
        assertEquals("PASO_OMITIDO", details.evaluacion().infraccion().tipo());
        assertEquals("PASO_2_DORSOS", details.evaluacion().infraccion().paso());
        assertEquals(1, details.evaluacion().progreso().pasosCompletados());
        assertEquals("ESPUMA_VISIBLE",
            details.evaluacion().coberturaJabon().get("PALMA_IZQUIERDA"));
        assertNotSame(domain, details.evaluacion());
        assertTrue(java.util.Arrays.stream(SessionDetailsResponse.class.getRecordComponents())
            .noneMatch(component -> component.getType().getPackageName().equals("com.handwash.model")));
    }

    @Test
    void projectsFailedAttemptHistoryWithoutReturningDomainObjects() {
        var attempt = new IntentoLavadoResumen(2, "REINICIADO", "paso omitido", 1500,
            Map.of("PASO_1_PALMAS", 800L),
            List.of(new Infraccion(TipoInfraccion.PASO_OMITIDO,
                "paso incorrecto", "PASO_3_INTERDIGITALES", "2026-10-02T00:00:00Z")));

        var response = mapper.toFailedAttempts("session", List.of(attempt));

        assertEquals("session", response.sessionId());
        assertTrue(response.soloMetadatos());
        assertEquals(2, response.intentosFallidos().get(0).numero());
        assertEquals(800L, response.intentosFallidos().get(0)
            .tiempoPorPasoMs().get("PASO_1_PALMAS"));
        assertEquals("PASO_OMITIDO", response.intentosFallidos().get(0)
            .infracciones().get(0).tipo());
        assertFalse(java.util.Arrays.stream(FailedAttemptsResponse.class.getRecordComponents())
            .anyMatch(component -> component.getType().getPackageName().equals("com.handwash.model")));
    }
}
