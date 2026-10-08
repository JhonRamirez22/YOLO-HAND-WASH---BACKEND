package com.handwash.security;

import com.handwash.agent.Receptor;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketTicketServiceTest {
    @Test
    void issuesOnlyForCurrentSessionCredentialsAndRejectsRevokedTickets() {
        SessionManager sessions = new SessionManager(new Receptor());
        String sessionId = sessions.crearSesion(TipoProtocolo.DOMESTICO);
        WebSocketTicketService tickets = new WebSocketTicketService(
            sessions, new WebSocketTicketRegistry());
        try {
            assertEquals(WebSocketTicketService.IssueOutcome.UNAUTHORIZED,
                tickets.issue(sessionId, "invalid").outcome());

            String oldDeviceToken = sessions.getDeviceToken(sessionId);
            var issued = tickets.issue(sessionId, oldDeviceToken);
            assertEquals(WebSocketTicketService.IssueOutcome.ISSUED, issued.outcome());
            assertNotNull(tickets.consume(sessionId, issued.issue().value()));

            var pending = tickets.issue(sessionId, oldDeviceToken);
            assertEquals(WebSocketTicketService.IssueOutcome.ISSUED, pending.outcome());
            assertNotNull(sessions.emitirTokenDispositivo(sessionId));
            assertNull(tickets.consume(sessionId, pending.issue().value()),
                "ticket issued to a rotated device credential must not upgrade a socket");
        } finally {
            sessions.eliminarSesion(sessionId);
        }
    }

    @Test
    void ticketCannotSurviveSessionRemoval() {
        SessionManager sessions = new SessionManager(new Receptor());
        String sessionId = sessions.crearSesion(TipoProtocolo.DOMESTICO);
        WebSocketTicketService tickets = new WebSocketTicketService(
            sessions, new WebSocketTicketRegistry());
        var issued = tickets.issue(sessionId, sessions.getOwnerToken(sessionId));
        sessions.eliminarSesion(sessionId);

        assertNull(tickets.consume(sessionId, issued.issue().value()));
    }
}
