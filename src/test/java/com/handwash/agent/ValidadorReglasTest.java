package com.handwash.agent;

import com.handwash.model.DeteccionEvento;
import com.handwash.model.PasoLavado;
import com.handwash.model.SesionLavado;
import com.handwash.model.TipoInfraccion;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ValidadorReglasTest {

    @Test
    void validatesPreviousStepAtTransitionTime() {
        SessionManager sessionManager = new SessionManager(new Receptor());
        String sessionId = sessionManager.crearSesion(TipoProtocolo.CLINICO_QUIRURGICO);
        SesionLavado sesion = sessionManager.getSesion(sessionId);

        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_000L);
        sesion.procesarDeteccion(PasoLavado.PASO_1_PALMAS, 1_500L);
        sesion.procesarDeteccion(PasoLavado.PASO_2_DORSOS, 1_800L); // first transition vote
        sesion.procesarDeteccion(PasoLavado.PASO_2_DORSOS, 2_100L); // confirms after only 0.8s

        ValidadorReglas validador = new ValidadorReglas(sessionManager);
        validador.onDeteccion(new DeteccionEvento(sessionId, PasoLavado.PASO_2_DORSOS.getClaseModelo(), 0.9f, "2026-01-01T00:00:00Z"));

        assertNotNull(sesion.getInfraccionActual());
        assertEquals(TipoInfraccion.TIEMPO_INSUFICIENTE, sesion.getInfraccionActual().getTipo());
        assertEquals("EN_PROGRESO", sesion.getEstadoSesion().name());
        assertEquals(PasoLavado.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
        assertEquals(0, sesion.getIntentosReiniciados());
        assertFalse(sesion.getInfraccionesIntentoActual().isEmpty());
    }
}
