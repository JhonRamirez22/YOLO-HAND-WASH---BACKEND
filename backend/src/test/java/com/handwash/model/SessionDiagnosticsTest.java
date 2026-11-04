package com.handwash.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionDiagnosticsTest {
    @Test void defaultDoesNotRetainDiagnosticEvents() {
        var session = new WashSession("test", ProtocolType.DOMESTICO);
        session.reiniciarIntento("EVIDENCIA_VISUAL_INTERRUPTA");
        assertNull(session.getDiagnosticResetEvents());
    }

    @Test void eventsAreCapturedAtResetEvenBeforeUiPollingAndRemainBounded() {
        var session = new WashSession("test", ProtocolType.DOMESTICO);
        session.setPhaseOneDiagnostics(true);
        session.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000);
        session.procesarDeteccion(WashStep.FONDO, 2_000);
        var event = session.getDiagnosticResetEvents().get(0);
        assertEquals("PASO_1_PALMAS", event.previousState());
        assertNull(event.nextState());
        assertEquals("EVIDENCIA_VISUAL_INTERRUPTA", event.reason());
        assertTrue(event.epochMillis() > 0);
        for (int i = 0; i < 260; i++) session.reiniciarIntento("TEST_RESET");
        assertEquals(256, session.getDiagnosticResetEvents().size());
        assertEquals(261, session.getDiagnosticResetEvents().get(255).sequence());
    }
}
