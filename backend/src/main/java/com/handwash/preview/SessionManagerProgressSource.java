package com.handwash.preview;

import com.handwash.model.WashStep;
import com.handwash.model.WashSession;
import com.handwash.service.SessionManager;
import org.springframework.stereotype.Component;

/** Adapter: reads the credited step from the session the state machine owns. */
@Component
public final class SessionManagerProgressSource implements SessionProgressSource {
    private final SessionManager sessions;

    public SessionManagerProgressSource(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public SessionProgress of(String sessionId) {
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) return SessionProgress.NONE;
        String name = session.getEstadoActualResponse().getEstadoActual();
        if (name == null) return SessionProgress.NONE;
        WashStep step;
        try { step = WashStep.valueOf(name); } catch (IllegalArgumentException unknown) { return SessionProgress.NONE; }
        int number = step.getNumero();
        if (number < 1 || number > 6) return SessionProgress.NONE;
        return new SessionProgress(number, number < 6 ? number + 1 : 0);
    }
}
