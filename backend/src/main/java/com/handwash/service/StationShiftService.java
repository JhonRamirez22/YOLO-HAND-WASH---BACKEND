package com.handwash.service;

import com.handwash.agent.Notifier;
import com.handwash.agent.SessionAuditObserver;
import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import com.handwash.model.SessionStatus;
import com.handwash.model.WashSession;
import com.handwash.ops.OpsAlert;
import com.handwash.model.StationRecord;
import com.handwash.model.StationShift;
import com.handwash.model.StationShift.ShiftEndReason;
import com.handwash.model.ProtocolType;
import com.handwash.repository.StationShiftRepository;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Unattended operation. While a shift is open, Java keeps exactly one technical session on the
 * station: it is created automatically, it is not ended by silence at the sink, and it is closed
 * when the shift ends. A backend restart does not end the shift; the next tick starts a new session.
 */
@Service
@Profile("cloud")
public class StationShiftService {
    /** No presence pulse for this long during a shift raises PRODUCER_SILENT (alarm needs a full minute of it). */
    static final long PRODUCER_SILENCE_MS = 60_000L;
    private static final long SILENCE_ALERT_REPEAT_MS = 60_000L;
    private final java.util.Map<String, Long> lastSilenceAlert = new java.util.concurrent.ConcurrentHashMap<>();
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 16;
    private static final Logger log = LoggerFactory.getLogger(StationShiftService.class);

    public enum Outcome { STARTED, ALREADY_OPEN, INVALID }
    public record StartResult(Outcome outcome, StationShift shift) {}

    private final StationShiftRepository shifts;
    private final StationCredentialService stations;
    private final SessionManager sessions;
    private final CloudStationSessionStarter starter;
    private final SessionAuditService audit;
    private final SessionAuditObserver auditObserver;
    private final Notifier notifier;
    private final ValidationRuleStrategyFactory strategies;
    private final LongSupplier clockMillis;

    @Autowired
    public StationShiftService(StationShiftRepository shifts, StationCredentialService stations,
                               SessionManager sessions, CloudStationSessionStarter starter,
                               SessionAuditService audit, SessionAuditObserver auditObserver,
                               Notifier notifier, ValidationRuleStrategyFactory strategies) {
        this(shifts, stations, sessions, starter, audit, auditObserver, notifier, strategies,
            System::currentTimeMillis);
    }

    StationShiftService(StationShiftRepository shifts, StationCredentialService stations,
                        SessionManager sessions, CloudStationSessionStarter starter,
                        SessionAuditService audit, SessionAuditObserver auditObserver,
                        Notifier notifier, ValidationRuleStrategyFactory strategies,
                        LongSupplier clockMillis) {
        this.shifts = shifts;
        this.stations = stations;
        this.sessions = sessions;
        this.starter = starter;
        this.audit = audit;
        this.auditObserver = auditObserver;
        this.notifier = notifier;
        this.strategies = strategies;
        this.clockMillis = clockMillis;
    }

    public synchronized StartResult start(StationRecord station, String operatorSubject, int hours, String protocol) {
        final ProtocolType protocolo;
        try {
            protocolo = ProtocolType.fromRequestValue(protocol);
        } catch (IllegalArgumentException invalid) {
            return new StartResult(Outcome.INVALID, null);
        }
        if (hours < MIN_HOURS || hours > MAX_HOURS || !strategies.isDisponible(protocolo)) {
            return new StartResult(Outcome.INVALID, null);
        }
        Optional<StationShift> open = shifts.findOpenByStation(station.stationId());
        if (open.isPresent()) return new StartResult(Outcome.ALREADY_OPEN, open.get());
        long now = clockMillis.getAsLong();
        StationShift shift = new StationShift(UUID.randomUUID().toString(), station.stationId(), protocolo.value(),
            bounded(operatorSubject), now, now + TimeUnit.HOURS.toMillis(hours), null, null);
        shifts.insert(shift);
        audit.recordStationEvent(null, bounded(operatorSubject), "OPERATOR_SHIFT_STARTED", "STARTED",
            "HOURS_" + hours + ":" + station.stationId());
        ensureSession(station, shift);
        return new StartResult(Outcome.STARTED, shift);
    }

    public Optional<StationShift> openShift(String stationId) {
        return shifts.findOpenByStation(stationId);
    }

    /** Ends the open shift and its session; false when the station had no open shift. */
    public synchronized boolean stop(StationRecord station, String actor) {
        return end(station.stationId(), ShiftEndReason.STOPPED, bounded(actor), true);
    }

    /** An operator who stops the session ends the shift too, or the next tick would restart it. */
    public synchronized void endOnManualSessionStop(String stationId, String actor) {
        end(stationId, ShiftEndReason.STOPPED, bounded(actor), false);
    }

    @Scheduled(fixedDelayString = "${handwash.shift.tick-ms:5000}")
    public synchronized void tick() {
        final java.util.List<StationShift> open;
        try {
            open = shifts.findOpen();
        } catch (DataAccessException unavailable) {
            log.debug("Shift storage unavailable: {}", unavailable.getClass().getSimpleName());
            return;
        }
        long now = clockMillis.getAsLong();
        for (StationShift shift : open) {
            try {
                StationRecord station = stations.activeStation(shift.stationId());
                if (station == null) {
                    end(shift.stationId(), ShiftEndReason.STATION_REVOKED, "SYSTEM", true);
                } else if (now >= shift.endsAtEpochMs()) {
                    end(shift.stationId(), ShiftEndReason.EXPIRED, "SYSTEM", true);
                } else {
                    ensureSession(station, shift);
                }
            } catch (RuntimeException failure) {
                // One broken station must not stop the others; the next tick retries.
                log.debug("Shift tick failed: {}", failure.getClass().getSimpleName());
            }
        }
    }

    private void ensureSession(StationRecord station, StationShift shift) {
        String activeId = station.activeSessionId();
        if (activeId != null) {
            WashSession session = sessions.getSesion(activeId);
            if (isActive(session)) {
                session.setSesionDeTurno(true);
                alertIfProducerSilent(station.stationId(), session);
                // The browser needs a valid capability for the whole shift, not just the first hour.
                sessions.renovarTokenPropietarioSiVaAVencer(activeId);
            } else {
                stations.releaseSession(activeId); // stale reservation, e.g. after a restart
            }
            return;
        }
        var response = starter.start(station, new SessionCreateRequest(shift.protocolCode(), "2"),
            UUID.randomUUID().toString());
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() instanceof SessionCreatedResponse created) {
            WashSession session = sessions.getSesion(created.sessionId());
            if (session != null) session.setSesionDeTurno(true);
        } else {
            OpsAlert.raise(log, OpsAlert.Code.SHIFT_SESSION_START_FAILED, "status=" + response.getStatusCode().value());
        }
    }

    /** During a shift the producer sends a presence pulse constantly; silence means the PC, network or camera is down. */
    private void alertIfProducerSilent(String stationId, WashSession session) {
        long now = clockMillis.getAsLong();
        if (now - session.lastProducerPulseEpochMs() < PRODUCER_SILENCE_MS) {
            lastSilenceAlert.remove(stationId);
            return;
        }
        Long previous = lastSilenceAlert.get(stationId);
        if (previous == null || now - previous >= SILENCE_ALERT_REPEAT_MS) {
            lastSilenceAlert.put(stationId, now);
            OpsAlert.raise(log, OpsAlert.Code.PRODUCER_SILENT,
                "silentSeconds=" + (now - session.lastProducerPulseEpochMs()) / 1000);
        }
    }

    private boolean end(String stationId, ShiftEndReason reason, String actor, boolean closeSession) {
        Optional<StationShift> open = shifts.findOpenByStation(stationId);
        if (open.isEmpty()) return false;
        StationShift shift = open.get();
        if (shifts.close(shift.shiftId(), clockMillis.getAsLong(), reason) == 0) return false;
        audit.recordStationEvent(null, actor, reason == ShiftEndReason.STOPPED
                ? "OPERATOR_SHIFT_STOPPED" : "STATION_SHIFT_ENDED", reason.name(), stationId);
        if (closeSession) closeActiveSession(stationId, actor);
        return true;
    }

    private void closeActiveSession(String stationId, String actor) {
        StationRecord station = stations.activeStation(stationId);
        String sessionId = station == null ? null : station.activeSessionId();
        if (sessionId == null) return;
        WashSession session = sessions.getSesion(sessionId);
        if (session == null) {
            stations.releaseSession(sessionId);
            return;
        }
        audit.recordSessionDeleteRequested(sessionId, session.getNumeroIntentoActual());
        if (!sessions.eliminarSesion(sessionId)) {
            audit.recordSessionDeleteFailed(sessionId, session.getNumeroIntentoActual());
            return;
        }
        notifier.eliminarSesion(sessionId);
        auditObserver.forgetSession(sessionId);
        audit.recordSessionDeleted(sessionId, session.getNumeroIntentoActual());
    }

    private static boolean isActive(WashSession session) {
        return session != null && session.getEstadoSesion() != SessionStatus.COMPLETADA
            && session.getEstadoSesion() != SessionStatus.EXPIRADA;
    }

    private static String bounded(String value) {
        return value == null ? "SYSTEM" : value.length() <= 128 ? value : value.substring(0, 128);
    }
}
