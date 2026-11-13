package com.handwash.service;

import com.handwash.agent.Notifier;
import com.handwash.agent.SessionAuditObserver;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import com.handwash.decorator.strategy.MetricsWashStrategyDecoratorFactory;
import com.handwash.model.WashSession;
import com.handwash.model.StationRecord;
import com.handwash.model.StationShift;
import com.handwash.model.ProtocolType;
import com.handwash.repository.StationShiftRepository;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StationShiftServiceTest {
    private static final long T0 = 1_792_600_000_000L;
    private static final String STATION = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private final AtomicLong now = new AtomicLong(T0);
    private final StationCredentialService stations = mock(StationCredentialService.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final CloudStationSessionStarter starter = mock(CloudStationSessionStarter.class);
    private final SessionAuditService audit = mock(SessionAuditService.class);
    private final SessionAuditObserver observer = mock(SessionAuditObserver.class);
    private final Notifier notifier = mock(Notifier.class);
    private StationShiftService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        for (String id : List.of(STATION, OTHER)) {
            jdbc.update("INSERT INTO handwash_stations (station_id, station_name, credential_hash, created_at_epoch_ms, created_by) VALUES (?,?,?,?,?)",
                id, "Puesto " + id.charAt(0), id.replace("-", "") + id.replace("-", "").substring(0, 32), 1L, "admin");
        }
        var factory = new ValidationRuleStrategyFactory(new MetricsWashStrategyDecoratorFactory(HandwashMetrics.noop()));
        ReflectionTestUtils.setField(factory, "balancedFrictionEnabled", true);
        service = new StationShiftService(new StationShiftRepository(jdbc), stations, sessions, starter, audit,
            observer, notifier, factory, now::get);
        when(stations.activeStation(STATION)).thenReturn(station(STATION, null));
        when(stations.activeStation(OTHER)).thenReturn(station(OTHER, null));
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private static StationRecord station(String id, String activeSession) {
        return new StationRecord(id, "Puesto", 1L, "admin", null, null, activeSession, activeSession == null ? null : "req");
    }

    private WashSession session(String id) {
        var factory = new ValidationRuleStrategyFactory(new MetricsWashStrategyDecoratorFactory(HandwashMetrics.noop()));
        return new WashSession(id, ProtocolType.DOMESTICO, factory.crear(ProtocolType.DOMESTICO));
    }

    private void autoStartReturns(String sessionId) {
        doReturn(ResponseEntity.ok(new SessionCreatedResponse(
            sessionId, "AGUA_Y_JABON", "code", "token", "2026-01-01T00:00:00Z", "ok", "m", "a", false, List.of())))
            .when(starter).start(any(), any(), any());
        when(sessions.getSesion(sessionId)).thenReturn(session(sessionId));
    }

    @Test
    void startRejectsOutOfRangeHoursUnknownAndUnavailableProtocols() {
        var st = station(STATION, null);
        assertEquals(StationShiftService.Outcome.INVALID, service.start(st, "op", 0, "AGUA_Y_JABON").outcome());
        assertEquals(StationShiftService.Outcome.INVALID, service.start(st, "op", 17, "AGUA_Y_JABON").outcome());
        assertEquals(StationShiftService.Outcome.INVALID, service.start(st, "op", 8, "NO_EXISTE").outcome());
        assertEquals(StationShiftService.Outcome.INVALID, service.start(st, "op", 8, null).outcome());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM station_shifts", Integer.class));
        verifyNoInteractions(starter);
    }

    @Test
    void startOpensOneShiftAuditsItAndStartsTheSessionRightAway() {
        autoStartReturns("s1");

        var result = service.start(station(STATION, null), "operator-sub", 10, "AGUA_Y_JABON");

        assertEquals(StationShiftService.Outcome.STARTED, result.outcome());
        assertEquals(T0 + TimeUnit.HOURS.toMillis(10), result.shift().endsAtEpochMs());
        assertEquals("AGUA_Y_JABON", result.shift().protocolCode());
        verify(audit).recordStationEvent(null, "operator-sub", "OPERATOR_SHIFT_STARTED", "STARTED", "HOURS_10:" + STATION);
        verify(starter).start(any(), argThat(r -> "AGUA_Y_JABON".equals(r.protocolo()) && "2".equals(r.producerProtocolVersion())), any());
        assertEquals(StationShiftService.Outcome.ALREADY_OPEN, service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON").outcome());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM station_shifts WHERE ended_at_epoch_ms IS NULL", Integer.class));
    }

    @Test
    void tickStartsASessionWhenTheStationHasNoneAndKeepsItAliveWhileIdle() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");
        WashSession created = sessions.getSesion("s1");
        assertTrue(created.isSesionDeTurno());

        // Hours without anyone at the sink: the ordinary idle timeout must not end a shift session.
        long hoursLaterMs = TimeUnit.HOURS.toMillis(6);
        assertFalse(created.estaInactivaDesde(System.currentTimeMillis() + hoursLaterMs, System.nanoTime() + hoursLaterMs * 1_000_000L, 300_000L));
    }

    @Test
    void tickFlagsAnOperatorSessionAndReplacesAStaleReservation() {
        autoStartReturns("auto");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");
        clearInvocations(starter, stations);
        when(stations.activeStation(STATION)).thenReturn(station(STATION, "manual"));
        WashSession manual = session("manual");
        when(sessions.getSesion("manual")).thenReturn(manual);

        service.tick();
        assertTrue(manual.isSesionDeTurno());
        verify(sessions).renovarTokenPropietarioSiVaAVencer("manual");
        verify(starter, never()).start(any(), any(), any());

        when(sessions.getSesion("manual")).thenReturn(null);
        service.tick();
        verify(stations).releaseSession("manual");
        verify(starter, never()).start(any(), any(), any());
    }

    @Test
    void shiftEndClosesTheSessionAndTheShift() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 1, "AGUA_Y_JABON");
        when(stations.activeStation(STATION)).thenReturn(station(STATION, "s1"));
        when(sessions.eliminarSesion("s1")).thenReturn(true);
        now.addAndGet(TimeUnit.HOURS.toMillis(1) + 1);

        service.tick();

        assertEquals("EXPIRED", jdbc.queryForObject("SELECT end_reason FROM station_shifts", String.class));
        verify(audit).recordStationEvent(null, "SYSTEM", "STATION_SHIFT_ENDED", "EXPIRED", STATION);
        verify(sessions).eliminarSesion("s1");
        verify(notifier).eliminarSesion("s1");
        verify(auditObserver()).forgetSession("s1");
        assertTrue(service.openShift(STATION).isEmpty());
    }

    private SessionAuditObserver auditObserver() { return observer; }

    @Test
    void revokedStationEndsItsShift() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");
        when(stations.activeStation(STATION)).thenReturn(null);

        service.tick();

        assertEquals("STATION_REVOKED", jdbc.queryForObject("SELECT end_reason FROM station_shifts", String.class));
    }

    @Test
    void stopEndsTheShiftAndItsSessionOnlyOnce() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");
        when(stations.activeStation(STATION)).thenReturn(station(STATION, "s1"));
        when(sessions.eliminarSesion("s1")).thenReturn(true);

        assertTrue(service.stop(station(STATION, "s1"), "op"));
        assertFalse(service.stop(station(STATION, "s1"), "op"), "a second stop finds no open shift");
        verify(audit).recordStationEvent(null, "op", "OPERATOR_SHIFT_STOPPED", "STOPPED", STATION);
        verify(sessions, times(1)).eliminarSesion("s1");
    }

    @Test
    void manualSessionStopEndsTheShiftWithoutDeletingTheSessionAgain() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");

        service.endOnManualSessionStop(STATION, "op");

        assertEquals("STOPPED", jdbc.queryForObject("SELECT end_reason FROM station_shifts", String.class));
        verify(sessions, never()).eliminarSesion(any());
        clearInvocations(starter);
        service.tick();
        verify(starter, never()).start(any(), any(), any());
    }

    @Test
    void aFailingStationDoesNotStopTheOthersAndTheNextTickRetries() {
        autoStartReturns("s1");
        service.start(station(STATION, null), "op", 8, "AGUA_Y_JABON");
        service.start(station(OTHER, null), "op", 8, "AGUA_Y_JABON");
        clearInvocations(starter);
        doThrow(new IllegalStateException("boom")).when(starter)
            .start(argThat(s -> s != null && STATION.equals(s.stationId())), any(), any());
        doReturn(ResponseEntity.status(409).build()).when(starter)
            .start(argThat(s -> s != null && OTHER.equals(s.stationId())), any(), any());

        service.tick();

        verify(starter, times(2)).start(any(), any(), any());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM station_shifts WHERE ended_at_epoch_ms IS NULL", Integer.class));
    }

    @Test
    void shiftRecordIsOpenUntilClosed() {
        var open = new StationShift("s", STATION, "AGUA_Y_JABON", "op", 1, 2, null, null);
        var closed = new StationShift("s", STATION, "AGUA_Y_JABON", "op", 1, 2, 2L, StationShift.ShiftEndReason.EXPIRED);
        assertTrue(open.open());
        assertFalse(closed.open());
    }

    @Test
    void aSilentProducerRaisesOneAlertPerMinuteAndStopsWhenPulsesReturn() {
        var live = session("33333333-3333-3333-3333-333333333333");
        when(stations.activeStation(STATION)).thenReturn(station(STATION, live.getSessionId()));
        when(sessions.getSesion(live.getSessionId())).thenReturn(live);
        long pulse = live.lastProducerPulseEpochMs();
        now.set(pulse); // the shift must be open relative to the session's real creation time
        service.start(station(STATION, live.getSessionId()), "admin-sub", 4, "AGUA_Y_JABON");
        try (var capture = new com.handwash.ops.LogCapture(StationShiftService.class)) {
            now.set(pulse + 30_000);
            service.tick();
            assertEquals(0, capture.alerts(com.handwash.ops.OpsAlert.Code.PRODUCER_SILENT), "30 s of silence is normal jitter");

            now.set(pulse + 61_000);
            service.tick();
            now.set(pulse + 90_000);
            service.tick();
            assertEquals(1, capture.alerts(com.handwash.ops.OpsAlert.Code.PRODUCER_SILENT), "repeats are rate limited");

            now.set(pulse + 125_000);
            service.tick();
            assertEquals(2, capture.alerts(com.handwash.ops.OpsAlert.Code.PRODUCER_SILENT));

            live.observeFrictionPresence(0, 1L, 1_000L);
            now.set(live.lastProducerPulseEpochMs() + 1_000);
            service.tick();
            assertEquals(2, capture.alerts(com.handwash.ops.OpsAlert.Code.PRODUCER_SILENT), "pulses are back");
        }
    }

    @Test
    void aSessionThatCannotBeStartedRaisesAnAlert() {
        doReturn(ResponseEntity.status(503).build()).when(starter).start(any(), any(), any());
        service.start(station(STATION, null), "admin-sub", 4, "AGUA_Y_JABON");
        try (var capture = new com.handwash.ops.LogCapture(StationShiftService.class)) {
            service.tick();
            assertTrue(capture.alerts(com.handwash.ops.OpsAlert.Code.SHIFT_SESSION_START_FAILED) >= 1);
        }
    }
}
