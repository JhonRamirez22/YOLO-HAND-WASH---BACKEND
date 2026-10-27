package com.handwash.api.v1;

import com.handwash.api.v1.dto.WebRtcTicketResponse;
import com.handwash.security.SessionTokenResolver;
import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import com.handwash.webrtc.IceServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebRtcTicketControllerTest {
    private final WebRtcTicketService service = mock(WebRtcTicketService.class);
    private final SessionTokenResolver tokens = mock(SessionTokenResolver.class);

    @Test
    void ticketCarriesTheIceServersForTheRoleThatReceivedIt() {
        var turn = new IceServer(List.of("turns:turn.example.org:443?transport=tcp"), "u", "c");
        var controller = new WebRtcTicketController(service, tokens, (id, role) ->
            role == WebRtcTicketRegistry.PeerRole.BROWSER ? List.of(turn) : List.of());
        when(tokens.resolve(any(), any())).thenReturn("access");
        when(service.issue(eq("session-1"), any())).thenReturn(new WebRtcTicketService.Result(
            WebRtcTicketService.Outcome.ISSUED, new WebRtcTicketRegistry.Issued("t".repeat(43), 1_000L),
            WebRtcTicketRegistry.PeerRole.BROWSER));

        var response = controller.issue("session-1", null, "token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (WebRtcTicketResponse) response.getBody();
        assertEquals("BROWSER", body.peerRole());
        assertEquals(List.of(turn), body.iceServers());
    }

    @Test
    void withoutConfigurationTheListIsEmptyAndErrorsDoNotLeakServers() {
        var controller = new WebRtcTicketController(service, tokens, com.handwash.webrtc.IceServerProvider.none());
        when(service.issue(any(), any())).thenReturn(new WebRtcTicketService.Result(
            WebRtcTicketService.Outcome.UNAUTHORIZED, null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.issue("session-1", null, null).getStatusCode());

        when(service.issue(any(), any())).thenReturn(new WebRtcTicketService.Result(
            WebRtcTicketService.Outcome.ISSUED, new WebRtcTicketRegistry.Issued("t".repeat(43), 1_000L),
            WebRtcTicketRegistry.PeerRole.RTX_PRODUCER));
        var body = (WebRtcTicketResponse) controller.issue("session-1", null, "token").getBody();
        assertTrue(body.iceServers().isEmpty());
    }

    @Test
    void providerIsNotConsultedWhenNoTicketIsIssued() {
        var provider = mock(com.handwash.webrtc.IceServerProvider.class);
        var controller = new WebRtcTicketController(service, tokens, provider);
        when(service.issue(any(), any())).thenReturn(new WebRtcTicketService.Result(
            WebRtcTicketService.Outcome.CAPACITY, null, null));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, controller.issue("session-1", null, "token").getStatusCode());
        verify(provider, never()).servers(any(), any());
    }
}
