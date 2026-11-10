package com.handwash.security;

import com.handwash.agent.DetectionReceiver;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebRtcTicketServiceTest {
    @Test
    void terminatingASessionInvalidatesPendingTicketsAndPreventsNewOnes() {
        SessionManager sessions = new SessionManager(new DetectionReceiver());
        String id = sessions.crearSesion(ProtocolType.DOMESTICO, true);
        var service = new WebRtcTicketService(sessions, new WebRtcTicketRegistry());
        try {
            var ticket = service.issue(id, sessions.getOwnerToken(id));
            sessions.getSesion(id).expirar();
            assertNull(service.consume(id, ticket.issued().value()));
            assertEquals(WebRtcTicketService.Outcome.NOT_FOUND,
                service.issue(id, sessions.getOwnerToken(id)).outcome());
        } finally { sessions.eliminarSesion(id); }
    }

    @Test
    void ticketsAreRoleBoundSessionBoundAndSingleUse() {
        SessionManager sessions = new SessionManager(new DetectionReceiver());
        String sessionId = sessions.crearSesion(ProtocolType.DOMESTICO, true);
        WebRtcTicketService service = new WebRtcTicketService(sessions, new WebRtcTicketRegistry());
        try {
            var browser = service.issue(sessionId, sessions.getOwnerToken(sessionId));
            assertEquals(WebRtcTicketRegistry.PeerRole.BROWSER, browser.role());
            assertNotNull(service.consume(sessionId, browser.issued().value()));
            assertNull(service.consume(sessionId, browser.issued().value()));

            var producer = service.issue(sessionId, sessions.getDeviceToken(sessionId));
            assertEquals(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER, producer.role());
            assertNotNull(service.consume(sessionId, producer.issued().value()));

            var viewer = sessions.emitirTokenDashboard(sessionId);
            assertEquals(WebRtcTicketService.Outcome.UNAUTHORIZED,
                service.issue(sessionId, viewer.value()).outcome());
            var wrongSession = service.issue(sessionId, sessions.getOwnerToken(sessionId));
            assertNull(service.consume("00000000-0000-4000-8000-000000000000",
                wrongSession.issued().value()));
        } finally {
            sessions.eliminarSesion(sessionId);
        }
    }
}
