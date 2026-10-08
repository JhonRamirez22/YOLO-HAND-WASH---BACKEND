package com.handwash.security;

import com.handwash.model.EstadoSesion;
import com.handwash.model.SesionLavado;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Service;

/** Owns pairing-code logins without exposing credential maps or session role internals. */
@Service
public class SessionAuthenticationService {
    public enum Failure { INVALID_CODE, SESSION_TERMINATED, PRODUCER_VERSION_REQUIRED }

    public record DeviceLogin(SesionLavado session, SessionManager.TokenIssue token,
                              int protocolVersion, Failure failure) {
        public boolean successful() { return session != null && token != null && failure == null; }
    }

    public record DashboardLogin(SesionLavado session, SessionManager.TokenIssue token,
                                 Failure failure) {
        public boolean successful() { return session != null && token != null && failure == null; }
    }

    private final SessionManager sessionManager;

    public SessionAuthenticationService(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public DeviceLogin loginWithPairingCode(String code, int protocolVersion) {
        SesionLavado session = sessionManager.getSesionPorCodigo(code);
        if (session == null) return new DeviceLogin(null, null, protocolVersion, Failure.INVALID_CODE);
        synchronized (session) {
            if (sessionManager.getSesion(session.getSessionId()) != session
                || session.getEstadoSesion() == EstadoSesion.COMPLETADA
                || session.getEstadoSesion() == EstadoSesion.EXPIRADA) {
                return new DeviceLogin(null, null, protocolVersion, Failure.SESSION_TERMINATED);
            }
            if (protocolVersion == 2 && !sessionManager.requireProducerEpoch(session.getSessionId())) {
                return new DeviceLogin(null, null, protocolVersion, Failure.SESSION_TERMINATED);
            }
            if (protocolVersion == 1 && sessionManager.producerEpochRequired(session.getSessionId())) {
                return new DeviceLogin(session, null, protocolVersion, Failure.PRODUCER_VERSION_REQUIRED);
            }
            SessionManager.TokenIssue token = sessionManager.emitirTokenDispositivo(session.getSessionId());
            if (token == null) {
                return new DeviceLogin(null, null, protocolVersion, Failure.SESSION_TERMINATED);
            }
            return new DeviceLogin(session, token, protocolVersion, null);
        }
    }

    /** Authenticates the dashboard with a read-only token; it must not rotate a producer epoch. */
    public DashboardLogin loginDashboardWithPairingCode(String code) {
        SesionLavado session = sessionManager.getSesionPorCodigo(code);
        if (session == null) return new DashboardLogin(null, null, Failure.INVALID_CODE);
        synchronized (session) {
            if (sessionManager.getSesion(session.getSessionId()) != session
                || session.getEstadoSesion() == EstadoSesion.COMPLETADA
                || session.getEstadoSesion() == EstadoSesion.EXPIRADA) {
                return new DashboardLogin(null, null, Failure.SESSION_TERMINATED);
            }
            SessionManager.TokenIssue token = sessionManager.emitirTokenDashboard(session.getSessionId());
            if (token == null) {
                return new DashboardLogin(null, null, Failure.SESSION_TERMINATED);
            }
            return new DashboardLogin(session, token, null);
        }
    }
}
