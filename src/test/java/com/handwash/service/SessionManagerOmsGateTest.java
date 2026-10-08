package com.handwash.service;

import com.handwash.agent.Receiver;
import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandwashingSessionState;
import com.handwash.model.ProtocolType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerOmsGateTest {
    @Test
    void omsInputIsDisabledByDefaultOutsideConfiguredProfiles() {
        SessionManager manager = new SessionManager(new Receiver());
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        DetectionEvent event = new DetectionEvent(
            sessionId, "OMS_01_MOJAR_MANOS", 0.95f, Instant.now().toString());
        event.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 0L));

        assertThrows(IncompatibleDetectionModeException.class,
            () -> manager.procesarDeteccion(event));
        assertNull(manager.getSesion(sessionId).getModoEvaluacionSeleccionado());
    }

    @Test
    void disabledOmsInputCannotSwitchSessionToUnvalidatedProtocol() {
        SessionManager manager = new SessionManager(new Receiver());
        ReflectionTestUtils.setField(manager, "omsInputEnabled", false);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO);
        DetectionEvent event = new DetectionEvent(
            sessionId, "OMS_01_MOJAR_MANOS", 0.95f, Instant.now().toString());
        event.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 0L));

        IncompatibleDetectionModeException rejection = assertThrows(
            IncompatibleDetectionModeException.class, () -> manager.procesarDeteccion(event));

        assertTrue(rejection.getMessage().contains("modelo y la evidencia no están aprobados"));
        assertNull(manager.getSesion(sessionId).getModoEvaluacionSeleccionado());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, manager.getSesion(sessionId).getEstadoSesion());
    }
}
