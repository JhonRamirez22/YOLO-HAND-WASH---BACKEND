package com.handwash.security;

import com.handwash.model.WashSession;
import com.handwash.model.SessionStatus;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Service;

@Service
public final class WebRtcTicketService {
    public enum Outcome { ISSUED, NOT_FOUND, UNAUTHORIZED, CAPACITY }
    public record Result(Outcome outcome, WebRtcTicketRegistry.Issued issued,
                        WebRtcTicketRegistry.PeerRole role) {}

    private final SessionManager sessions;
    private final WebRtcTicketRegistry tickets;

    public WebRtcTicketService(SessionManager sessions, WebRtcTicketRegistry tickets) {
        this.sessions = sessions;
        this.tickets = tickets;
    }

    public Result issue(String sessionId, String token) {
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return new Result(Outcome.NOT_FOUND, null, null);
        synchronized (session) {
            if (sessions.getSesion(sessionId) != session) return new Result(Outcome.NOT_FOUND, null, null);
            if (session.getEstadoSesion() == SessionStatus.EXPIRADA
                || session.getEstadoSesion() == SessionStatus.COMPLETADA) {
                return new Result(Outcome.NOT_FOUND, null, null);
            }
            SessionManager.AccessInfo access = sessions.autenticar(sessionId, token);
            if (access == null) return new Result(Outcome.UNAUTHORIZED, null, null);
            WebRtcTicketRegistry.PeerRole role = switch (access.role()) {
                case OWNER -> WebRtcTicketRegistry.PeerRole.BROWSER;
                case DEVICE -> WebRtcTicketRegistry.PeerRole.RTX_PRODUCER;
                default -> null;
            };
            if (role == null) return new Result(Outcome.UNAUTHORIZED, null, null);
            try {
                return new Result(Outcome.ISSUED, tickets.issue(sessionId, role, access), role);
            } catch (IllegalStateException full) {
                return new Result(Outcome.CAPACITY, null, null);
            }
        }
    }

    public WebRtcTicketRegistry.Grant consume(String sessionId, String ticket) {
        WebRtcTicketRegistry.Grant grant = tickets.consume(sessionId, ticket);
        WashSession session = sessions.getSesion(sessionId);
        if (grant == null || session == null) return null;
        synchronized (session) {
            if (sessions.getSesion(sessionId) != session
                || session.getEstadoSesion() == SessionStatus.EXPIRADA
                || session.getEstadoSesion() == SessionStatus.COMPLETADA
                || !sessions.credencialVigente(sessionId, grant.access())) return null;
            return grant;
        }
    }
}
