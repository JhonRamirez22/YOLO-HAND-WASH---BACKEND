package com.handwash.agent;

import com.handwash.model.DetectionEvent;
import com.handwash.model.WashStep;
import com.handwash.model.WashSession;
import com.handwash.model.InfractionType;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuleValidatorTest {

    @Test
    void validatesPreviousStepAtTransitionTime() {
        SessionManager sessionManager = new SessionManager(new DetectionReceiver());
        String sessionId = sessionManager.crearSesion(ProtocolType.CLINICO_QUIRURGICO);
        WashSession sesion = sessionManager.getSesion(sessionId);

        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L);
        sesion.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_500L);
        sesion.procesarDeteccion(WashStep.PASO_2_DORSOS, 1_800L); // first transition vote
        sesion.procesarDeteccion(WashStep.PASO_2_DORSOS, 2_100L); // confirms after only 0.8s

        RuleValidator validador = new RuleValidator(sessionManager);
        validador.onDeteccion(new DetectionEvent(sessionId, WashStep.PASO_2_DORSOS.getClaseModelo(), 0.9f, "2026-01-01T00:00:00Z"));

        assertNotNull(sesion.getInfraccionActual());
        assertEquals(InfractionType.TIEMPO_INSUFICIENTE, sesion.getInfraccionActual().getTipo());
        assertEquals("EN_PROGRESO", sesion.getEstadoSesion().name());
        assertEquals(WashStep.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
        assertEquals(0, sesion.getIntentosReiniciados());
        assertFalse(sesion.getInfraccionesIntentoActual().isEmpty());
    }
}
