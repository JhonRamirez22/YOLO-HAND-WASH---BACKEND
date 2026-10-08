package com.handwash.service;

import com.handwash.agent.Receptor;
import com.handwash.model.DeteccionEvento;
import com.handwash.model.EvidenciaMovimiento;
import com.handwash.model.EstadoSesion;
import com.handwash.model.TipoProtocolo;
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
        SessionManager manager = new SessionManager(new Receptor());
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        DeteccionEvento event = new DeteccionEvento(
            sessionId, "OMS_01_MOJAR_MANOS", 0.95f, Instant.now().toString());
        event.setEvidenciaMovimiento(new EvidenciaMovimiento(1L, 2, 0.2, true, 0L));

        assertThrows(ModoDeteccionIncompatibleException.class,
            () -> manager.procesarDeteccion(event));
        assertNull(manager.getSesion(sessionId).getModoEvaluacionSeleccionado());
    }

    @Test
    void disabledOmsInputCannotSwitchSessionToUnvalidatedProtocol() {
        SessionManager manager = new SessionManager(new Receptor());
        ReflectionTestUtils.setField(manager, "omsInputEnabled", false);
        String sessionId = manager.crearSesion(TipoProtocolo.DOMESTICO);
        DeteccionEvento event = new DeteccionEvento(
            sessionId, "OMS_01_MOJAR_MANOS", 0.95f, Instant.now().toString());
        event.setEvidenciaMovimiento(new EvidenciaMovimiento(1L, 2, 0.2, true, 0L));

        ModoDeteccionIncompatibleException rejection = assertThrows(
            ModoDeteccionIncompatibleException.class, () -> manager.procesarDeteccion(event));

        assertTrue(rejection.getMessage().contains("modelo y la evidencia no están aprobados"));
        assertNull(manager.getSesion(sessionId).getModoEvaluacionSeleccionado());
        assertEquals(EstadoSesion.ESPERANDO_INICIO, manager.getSesion(sessionId).getEstadoSesion());
    }
}
