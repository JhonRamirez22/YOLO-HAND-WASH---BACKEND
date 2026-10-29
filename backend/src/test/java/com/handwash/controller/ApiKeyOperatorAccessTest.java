package com.handwash.controller;

import com.handwash.agent.Notifier;
import com.handwash.agent.SessionAuditObserver;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.SyntheticTokens;
import com.handwash.service.CloudStationSessionStarter;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.service.StationShiftService;
import com.handwash.security.WebSocketTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Seam: the operator controller seen by a console-key session; the key's grant is what authorises the station. */
class ApiKeyOperatorAccessTest {
    private EmbeddedDatabase database;
    private ApiKeyService keys;
    private OperatorAccessController controller;
    private SessionManager sessionManager;
    private com.handwash.security.WebRtcTicketService webrtcTickets;
    private com.handwash.webrtc.IceServerProvider iceServers;
    private String stationId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new StationCredentialService(new StationRepository(jdbc), audit);
        var grants = new StationOperatorAccessRepository(jdbc);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, grants, audit);
        var access = new StationOperatorAccessService(grants, stations, audit);
        stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        sessionManager = mock(SessionManager.class);
        webrtcTickets = mock(com.handwash.security.WebRtcTicketService.class);
        iceServers = mock(com.handwash.webrtc.IceServerProvider.class);
        controller = new OperatorAccessController(access, stations, sessionManager, audit,
            mock(SessionAuditObserver.class), mock(CloudStationSessionStarter.class), mock(RequestRateLimiter.class),
            mock(Notifier.class), mock(WebSocketTicketService.class), mock(JwtDecoder.class), "cognito-spa",
            mock(StationShiftService.class), webrtcTickets, iceServers);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void aConsoleKeyOperatesItsStationAndLosesAccessWhenRevoked() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();

        ResponseEntity<?> me = controller.me(principal);
        assertEquals(200, me.getStatusCode().value());

        keys.revoke(issued.keyId(), "admin-sub");
        assertEquals(404, controller.me(principal).getStatusCode().value());
    }

    @Test
    void aConsoleKeyCanGiveRehearsalFeedbackOnlyOnItsStationsActiveSession() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        new StationRepository(new JdbcTemplate(database)).reserveSession(stationId, sessionId,
            "33333333-3333-4333-8333-333333333333", System.currentTimeMillis());
        var session = new com.handwash.model.WashSession(sessionId, com.handwash.model.ProtocolType.DOMESTICO);
        org.mockito.Mockito.when(sessionManager.getSesion(sessionId)).thenReturn(session);

        assertEquals(204, controller.stepFeedback(principal, sessionId,
            new com.handwash.api.v1.dto.StepFeedbackRequest(3, "CONFIRMADO", null)).getStatusCode().value());
        assertEquals(1, new JdbcTemplate(database).queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'OPERATOR_STEP_FEEDBACK'
            AND step_code = 'Paso3_Interdigitales' AND decision_code = 'CONFIRMED'
            """, Integer.class));
        assertEquals(400, controller.stepFeedback(principal, sessionId,
            new com.handwash.api.v1.dto.StepFeedbackRequest(9, "CONFIRMADO", null)).getStatusCode().value());
        assertEquals(403, controller.stepFeedback(principal, "22222222-2222-4222-8222-222222222222",
            new com.handwash.api.v1.dto.StepFeedbackRequest(3, "CONFIRMADO", null)).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConsoleKeyGetsAWebRtcTicketForItsStationsActiveSessionWithoutAStationCredential() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        new StationRepository(new JdbcTemplate(database)).reserveSession(stationId, sessionId,
            "33333333-3333-4333-8333-333333333333", System.currentTimeMillis());
        org.mockito.Mockito.when(sessionManager.getOwnerToken(sessionId)).thenReturn("owner-token");
        org.mockito.Mockito.when(webrtcTickets.issue(sessionId, "owner-token")).thenReturn(
            new com.handwash.security.WebRtcTicketService.Result(com.handwash.security.WebRtcTicketService.Outcome.ISSUED,
                new com.handwash.security.WebRtcTicketRegistry.Issued("t".repeat(40), System.currentTimeMillis() + 60_000),
                com.handwash.security.WebRtcTicketRegistry.PeerRole.BROWSER));
        org.mockito.Mockito.when(iceServers.servers(sessionId, com.handwash.security.WebRtcTicketRegistry.PeerRole.BROWSER))
            .thenReturn(java.util.List.of(new com.handwash.webrtc.IceServer(java.util.List.of("stun:stun.example:19302"), null, null)));

        var response = controller.webrtcTicket(principal, sessionId);

        assertEquals(200, response.getStatusCode().value());
        var body = (com.handwash.api.v1.dto.WebRtcTicketResponse) response.getBody();
        assertEquals("t".repeat(40), body.ticket());
        assertEquals("BROWSER", body.peerRole());
        assertEquals(1, body.iceServers().size());
        assertEquals(403, controller.webrtcTicket(principal, "22222222-2222-4222-8222-222222222222").getStatusCode().value());
    }

    @Test
    void aProducerKeyPrincipalIsNeverAnOperator() {
        var issued = keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub");

        assertEquals(403, controller.me(SyntheticTokens.producer(issued.keyId()).getToken()).getStatusCode().value());
    }
}
