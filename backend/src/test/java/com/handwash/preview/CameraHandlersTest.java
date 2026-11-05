package com.handwash.preview;

import com.handwash.security.WebRtcTicketRegistry;
import com.handwash.security.WebRtcTicketService;
import com.handwash.security.WebSocketTicketService;
import com.handwash.model.WashSession;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CameraHandlersTest {
    private static byte[] frame(int seq) {
        return new byte[] {0, 0, 0, (byte) seq, (byte) 0xFF, (byte) 0xD8, 9, 9};
    }

    private WebRtcTicketService tickets;
    private WebSocketTicketService viewerTickets;
    private SessionManager sessions;
    private CameraFrameHub hub;
    private CameraFrameIngressHandler phone;
    private CameraFrameEgressHandler pc;
    private SessionProgressSource progress;
    private PreviewSocketHandler panel;

    @BeforeEach
    void setUp() {
        tickets = mock(WebRtcTicketService.class);
        viewerTickets = mock(WebSocketTicketService.class);
        sessions = mock(SessionManager.class);
        hub = new CameraFrameHub();
        phone = new CameraFrameIngressHandler(tickets, hub);
        progress = mock(SessionProgressSource.class);
        when(progress.of("s1")).thenReturn(new SessionProgress(1, 2));
        pc = new CameraFrameEgressHandler(tickets, hub, progress);
        panel = new PreviewSocketHandler(sessions, viewerTickets, hub);
        when(sessions.getSesion("s1")).thenReturn(mock(WashSession.class));
    }

    private WebSocketSession socket(String id, String path) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getUri()).thenReturn(new URI("wss://api.example" + path + "?ticket=" + "t".repeat(43)));
        when(s.getAttributes()).thenReturn(new HashMap<>());
        return s;
    }

    private static WebRtcTicketRegistry.Grant grant(WebRtcTicketRegistry.PeerRole role) {
        return new WebRtcTicketRegistry.Grant("s1", role, new SessionManager.AccessInfo(SessionManager.AccessRole.OWNER, null, null, 1L), Long.MAX_VALUE);
    }

    @Test
    void aPhoneFrameReachesThePcAndThePanelAndThePcOverlayReachesOnlyThePanel() throws Exception {
        var phoneSocket = socket("p", "/ws/camera-in/s1"); var pcSocket = socket("c", "/ws/camera-out/s1"); var panelSocket = socket("v", "/ws/preview/s1");
        when(tickets.consume(eq("s1"), any())).thenReturn(grant(WebRtcTicketRegistry.PeerRole.BROWSER)).thenReturn(grant(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER));
        when(viewerTickets.consume(eq("s1"), any())).thenReturn(new SessionManager.AccessInfo(SessionManager.AccessRole.OWNER, null, null, 1L));

        phone.afterConnectionEstablished(phoneSocket);
        pc.afterConnectionEstablished(pcSocket);
        panel.afterConnectionEstablished(panelSocket);
        phone.handleBinaryMessage(phoneSocket, new BinaryMessage(frame(1)));
        pc.handleTextMessage(pcSocket, new TextMessage("{\"seq\":1,\"hands\":[]}"));

        verify(pcSocket).sendMessage(any(BinaryMessage.class));
        verify(panelSocket).sendMessage(any(BinaryMessage.class));
        verify(panelSocket).sendMessage(any(TextMessage.class));
        // The PC only hears back Java's progress (once, because it did not change), never the overlay.
        var hint = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        pc.handleTextMessage(pcSocket, new TextMessage("{\"seq\":2,\"hands\":[]}"));
        verify(pcSocket).sendMessage(hint.capture());
        assertThat(hint.getValue().getPayload()).isEqualTo("{\"hint\":{\"cur\":1,\"exp\":2}}");
    }

    @Test
    void thePanelOverlayCarriesJavasProgressAndThePcIsToldAgainWhenItChanges() throws Exception {
        var pcSocket = socket("c", "/ws/camera-out/s1"); var panelSocket = socket("v", "/ws/preview/s1");
        when(tickets.consume(eq("s1"), any())).thenReturn(grant(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER));
        when(viewerTickets.consume(eq("s1"), any())).thenReturn(new SessionManager.AccessInfo(SessionManager.AccessRole.OWNER, null, null, 1L));
        pc.afterConnectionEstablished(pcSocket);
        panel.afterConnectionEstablished(panelSocket);

        pc.handleTextMessage(pcSocket, new TextMessage("{\"seq\":1,\"hands\":[],\"cur\":0,\"exp\":1}"));
        var overlay = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(panelSocket).sendMessage(overlay.capture());
        assertThat(overlay.getValue().getPayload()).contains("\"cur\":1").contains("\"exp\":2");

        when(progress.of("s1")).thenReturn(new SessionProgress(2, 3));
        pc.handleTextMessage(pcSocket, new TextMessage("{\"seq\":2,\"hands\":[]}"));
        verify(pcSocket, org.mockito.Mockito.times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void theWrongTicketRoleOrNoTicketIsRefusedOnEachChannel() throws Exception {
        var phoneSocket = socket("p", "/ws/camera-in/s1"); var pcSocket = socket("c", "/ws/camera-out/s1");
        when(tickets.consume(eq("s1"), any())).thenReturn(grant(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER)).thenReturn(grant(WebRtcTicketRegistry.PeerRole.BROWSER));

        phone.afterConnectionEstablished(phoneSocket);   // got a producer ticket: refused
        pc.afterConnectionEstablished(pcSocket);          // got a browser ticket: refused

        verify(phoneSocket).close(any(CloseStatus.class));
        verify(pcSocket).close(any(CloseStatus.class));
        phone.handleBinaryMessage(phoneSocket, new BinaryMessage(frame(1)));
        verify(pcSocket, never()).sendMessage(any());
    }

    @Test
    void aNewCameraReplacesTheOldOneAndTheOldOnesFramesAreIgnored() throws Exception {
        var first = socket("p1", "/ws/camera-in/s1"); var second = socket("p2", "/ws/camera-in/s1"); var pcSocket = socket("c", "/ws/camera-out/s1");
        when(tickets.consume(eq("s1"), any())).thenReturn(grant(WebRtcTicketRegistry.PeerRole.BROWSER), grant(WebRtcTicketRegistry.PeerRole.BROWSER), grant(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER));
        phone.afterConnectionEstablished(first);
        phone.afterConnectionEstablished(second);
        pc.afterConnectionEstablished(pcSocket);

        phone.handleBinaryMessage(first, new BinaryMessage(frame(1)));
        verify(pcSocket, never()).sendMessage(any());
        phone.handleBinaryMessage(second, new BinaryMessage(frame(2)));

        verify(first).close(any(CloseStatus.class));
        verify(pcSocket).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void aSecondPcOnTheSameSessionIsRefused() throws Exception {
        var one = socket("c1", "/ws/camera-out/s1"); var two = socket("c2", "/ws/camera-out/s1");
        when(tickets.consume(eq("s1"), any())).thenReturn(grant(WebRtcTicketRegistry.PeerRole.RTX_PRODUCER));
        pc.afterConnectionEstablished(one);
        pc.afterConnectionEstablished(two);
        verify(two).close(any(CloseStatus.class));
        verify(one, never()).close(any(CloseStatus.class));
    }

    @Test
    void afterTheViewerClosesItStopsReceiving() throws Exception {
        var panelSocket = socket("v", "/ws/preview/s1");
        when(viewerTickets.consume(eq("s1"), any())).thenReturn(new SessionManager.AccessInfo(SessionManager.AccessRole.OWNER, null, null, 1L));
        panel.afterConnectionEstablished(panelSocket);
        panel.afterConnectionClosed(panelSocket, CloseStatus.NORMAL);
        hub.publishFrame("s1", frame(1));
        verify(panelSocket, never()).sendMessage(any());
    }
}
