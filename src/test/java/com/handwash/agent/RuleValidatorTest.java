package com.handwash.agent;

import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingStep;
import com.handwash.model.HandwashingSession;
import com.handwash.model.ViolationType;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuleValidatorTest {

    @Test
    void validatesPreviousStepAtTransitionTime() {
        SessionManager sessionManager = new SessionManager(new Receiver());
        String sessionId = sessionManager.crearSesion(ProtocolType.CLINICO_QUIRURGICO);
        HandwashingSession sesion = sessionManager.getSesion(sessionId);

        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_000L);
        sesion.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, 1_500L);
        sesion.procesarDeteccion(HandwashingStep.PASO_2_DORSOS, 1_800L); // first transition vote
        sesion.procesarDeteccion(HandwashingStep.PASO_2_DORSOS, 2_100L); // confirms after only 0.8s

        RuleValidator validador = new RuleValidator(sessionManager);
        validador.onDeteccion(new DetectionEvent(sessionId, HandwashingStep.PASO_2_DORSOS.getClaseModelo(), 0.9f, "2026-01-01T00:00:00Z"));

        assertNotNull(sesion.getInfraccionActual());
        assertEquals(ViolationType.TIEMPO_INSUFICIENTE, sesion.getInfraccionActual().getTipo());
        assertEquals("EN_PROGRESO", sesion.getEstadoSesion().name());
        assertEquals(HandwashingStep.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
        assertEquals(0, sesion.getIntentosReiniciados());
        assertFalse(sesion.getInfraccionesIntentoActual().isEmpty());
    }
}
