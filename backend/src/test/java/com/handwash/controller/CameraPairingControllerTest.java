package com.handwash.controller;

import com.handwash.api.v1.dto.WebRtcTicketResponse;
import com.handwash.model.StationRecord;
import com.handwash.security.CameraPairingRegistry;
import com.handwash.security.SyntheticTokens;
import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.webrtc.IceServerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The panel shows a QR; a phone trades it for a camera-only token and then fetches video tickets with it. */
class CameraPairingControllerTest {
    private static final String STATION = "25d9475e-9608-48ae-8e79-b1a80ed8d860";
    private static final String SESSION = "e4b82c6a-2850-496e-82ed-5a4db3f85213";

    private CameraPairingRegistry pairing;
    private StationOperatorAccessService access;
    private StationCredentialService stations;
    private SessionManager sessions;
    private WebRtcTicketService tickets;
    private CameraPairingController controller;
    private final Jwt console = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
        Map.of("sub", "apikey_f27f63a7573142dd", "client_id", SyntheticTokens.CONSOLE_CLIENT_ID));

    @BeforeEach
    void setUp() {
        pairing = new CameraPairingRegistry();
        access = mock(StationOperatorAccessService.class);
        stations = mock(StationCredentialService.class);
        sessions = mock(SessionManager.class);
        tickets = mock(WebRtcTicketService.class);
        IceServerProvider ice = (id, role) -> java.util.List.of();
        controller = new CameraPairingController(pairing, access, stations, sessions, tickets, ice);
        when(access.activeGrantForUpdate(console.getSubject())).thenReturn(
            new StationOperatorAccessService.GrantView(console.getSubject(), "", STATION, "LAVAMANOS TEST", "admin", 1L, null));
        when(stations.activeStation(STATION)).thenReturn(new StationRecord(STATION, "LAVAMANOS TEST", 1L, "admin", null, null, SESSION, null));
        when(sessions.getSesion(SESSION)).thenReturn(mock(com.handwash.model.WashSession.class));
        when(sessions.getOwnerToken(SESSION)).thenReturn("owner-token");
        when(tickets.issue(eq(SESSION), eq("owner-token"))).thenReturn(new WebRtcTicketService.Result(
            WebRtcTicketService.Outcome.ISSUED, new WebRtcTicketRegistry.Issued("t".repeat(43), System.currentTimeMillis() + 60_000),
            WebRtcTicketRegistry.PeerRole.BROWSER));
    }

    @SuppressWarnings("unchecked")
    private String pairedToken() {
        String code = (String) ((Map<String, Object>) controller.pair(console).getBody()).get("code");
        var response = controller.redeem(new CameraPairingController.RedeemRequest(code));
        assertEquals(200, response.getStatusCode().value());
        return (String) ((Map<String, Object>) response.getBody()).get("cameraToken");
    }

    @Test
    void onlyAConsoleSignedInWithAKeyCanAskForAQr() {
        assertEquals(200, controller.pair(console).getStatusCode().value());
        assertEquals(403, controller.pair(null).getStatusCode().value());
        Jwt other = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("sub", "x", "client_id", "other"));
        assertEquals(403, controller.pair(other).getStatusCode().value());
    }

    @Test
    void aPhoneTradesTheCodeOnceAndReceivesNoConsoleAccess() {
        String code = (String) ((Map<?, ?>) controller.pair(console).getBody()).get("code");
        var first = controller.redeem(new CameraPairingController.RedeemRequest(code));
        var again = controller.redeem(new CameraPairingController.RedeemRequest(code));

        assertEquals(200, first.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) first.getBody();
        assertEquals("LAVAMANOS TEST", body.get("stationName"));
        assertFalse(body.containsKey("sessionToken"), "a camera gets no console session");
        assertEquals(401, again.getStatusCode().value());
        assertEquals(400, controller.redeem(new CameraPairingController.RedeemRequest("")).getStatusCode().value());
    }

    @Test
    void theCameraTokenFetchesAVideoTicketForTheActiveSessionOfItsStationOnly() {
        String token = pairedToken();

        ResponseEntity<?> response = controller.ticket(token);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(SESSION, body.get("sessionId"));
        WebRtcTicketResponse ticket = (WebRtcTicketResponse) body.get("ticket");
        assertEquals("BROWSER", ticket.peerRole());
        assertEquals(401, controller.ticket("garbage").getStatusCode().value());
        assertEquals(401, controller.ticket(null).getStatusCode().value());
    }

    @Test
    void theStateEndpointTellsTheActiveSessionWithoutIssuingAnyTicket() {
        String token = pairedToken();

        ResponseEntity<?> response = controller.state(token);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(SESSION, ((Map<?, ?>) response.getBody()).get("sessionId"));
        org.mockito.Mockito.verifyNoInteractions(tickets);
        assertEquals(401, controller.state("garbage").getStatusCode().value());
        pairedToken();
        assertEquals(409, controller.state(token).getStatusCode().value());
    }

    @Test
    void withoutAnActiveSessionTheCameraWaits() {
        String token = pairedToken();
        when(stations.activeStation(STATION)).thenReturn(new StationRecord(STATION, "LAVAMANOS TEST", 1L, "admin", null, null, null, null));

        assertEquals(204, controller.ticket(token).getStatusCode().value());
    }

    @Test
    void aReplacedCameraIsToldSoInsteadOfRetryingForever() {
        String old = pairedToken();
        pairedToken();

        ResponseEntity<?> response = controller.ticket(old);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("CAMARA_REEMPLAZADA", ((com.handwash.api.v1.dto.ApiErrorResponse) response.getBody()).error());
    }
}
