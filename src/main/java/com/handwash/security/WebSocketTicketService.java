package com.handwash.security;

import com.handwash.model.SesionLavado;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Service;

/** Exchanges a session credential for a bounded, one-use WebSocket capability. */
@Service
public final class WebSocketTicketService {
    public enum IssueOutcome { ISSUED, NOT_FOUND, UNAUTHORIZED, CAPACITY }
    public record IssueResult(IssueOutcome outcome, WebSocketTicketRegistry.TicketIssue issue) {}

    private final SessionManager sessionManager;
    private final WebSocketTicketRegistry ticketRegistry;

    public WebSocketTicketService(SessionManager sessionManager,
                                  WebSocketTicketRegistry ticketRegistry) {
        this.sessionManager = sessionManager;
        this.ticketRegistry = ticketRegistry;
    }

    public IssueResult issue(String sessionId, String accessToken) {
        SesionLavado session = sessionManager.getSesion(sessionId);
        if (session == null) return new IssueResult(IssueOutcome.NOT_FOUND, null);
        synchronized (session) {
            if (sessionManager.getSesion(sessionId) != session) {
                return new IssueResult(IssueOutcome.NOT_FOUND, null);
            }
            SessionManager.AccessInfo access = sessionManager.autenticar(sessionId, accessToken);
            if (access == null) return new IssueResult(IssueOutcome.UNAUTHORIZED, null);
            try {
                return new IssueResult(IssueOutcome.ISSUED, ticketRegistry.issue(sessionId, access));
            } catch (WebSocketTicketRegistry.TicketCapacityException full) {
                return new IssueResult(IssueOutcome.CAPACITY, null);
            } catch (WebSocketTicketRegistry.TicketExpiredException expired) {
                return new IssueResult(IssueOutcome.UNAUTHORIZED, null);
            }
        }
    }

    /** Consumes once, then rechecks session identity and credential revocation under its lock. */
    public SessionManager.AccessInfo consume(String sessionId, String ticket) {
        WebSocketTicketRegistry.TicketGrant grant = ticketRegistry.consume(sessionId, ticket);
        if (grant == null) return null;
        SesionLavado session = sessionManager.getSesion(sessionId);
        if (session == null) return null;
        synchronized (session) {
            if (sessionManager.getSesion(sessionId) != session
                || !sessionManager.credencialVigente(sessionId, grant.accessInfo())) return null;
            return grant.accessInfo();
        }
    }
}
