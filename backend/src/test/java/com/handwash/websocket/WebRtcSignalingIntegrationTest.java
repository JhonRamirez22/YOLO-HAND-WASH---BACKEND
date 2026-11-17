package com.handwash.websocket;

import com.handwash.model.ProtocolType;
import com.handwash.security.WebRtcTicketService;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebRtcSignalingIntegrationTest {
    @Autowired SessionManager sessions;
    @Autowired WebRtcTicketService tickets;
    @Autowired WebRtcSignalingHandler handler;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test
    void realTomcatRelaysLargeOffersAndReplacementWithoutFalseDisconnect() throws Exception {
        String id = sessions.crearSesion(ProtocolType.DOMESTICO, true);
        WebSocket browser = null, producer = null, replacement = null;
        try {
            Inbox browserInbox = new Inbox();
            Inbox producerInbox = new Inbox();
            browser = connect(id, sessions.getOwnerToken(id), browserInbox);
            producer = connect(id, sessions.getDeviceToken(id), producerInbox);
            assertEquals("peer-ready", browserInbox.next().path("type").asText());
            assertEquals("peer-ready", producerInbox.next().path("type").asText());
            String sdp = "v=0\r\n" + "a=x\r\n".repeat(3000);
            browser.sendText(json.writeValueAsString(java.util.Map.of("type", "offer",
                "sdp", java.util.Map.of("type", "offer", "sdp", sdp))), true).join();
            assertEquals(sdp, producerInbox.next().path("sdp").path("sdp").asText());

            Inbox replacementInbox = new Inbox();
            replacement = connect(id, sessions.getOwnerToken(id), replacementInbox);
            assertEquals("peer-ready", replacementInbox.next().path("type").asText());
            assertEquals("peer-ready", producerInbox.next().path("type").asText());
            browserInbox.closed.get(3, TimeUnit.SECONDS);
            replacement.sendText("{\"type\":\"candidate\",\"candidate\":\"new-ice\",\"sdpMLineIndex\":0}", true).join();
            assertEquals("new-ice", producerInbox.next().path("candidate").asText());
            assertNull(producerInbox.messages.poll(100, TimeUnit.MILLISECONDS),
                "the old browser's close must not emit peer-left after replacement");

            sessions.emitirTokenDispositivo(id);
            handler.closeUnusablePeers();
            producerInbox.closed.get(3, TimeUnit.SECONDS);
            assertEquals("peer-left", replacementInbox.next().path("type").asText());
            sessions.getSesion(id).expirar();
            handler.closeUnusablePeers();
            replacementInbox.closed.get(3, TimeUnit.SECONDS);
        } finally {
            if (browser != null) browser.abort();
            if (producer != null) producer.abort();
            if (replacement != null) replacement.abort();
            sessions.eliminarSesion(id);
            handler.closeUnusablePeers();
        }
    }

    private WebSocket connect(String id, String token, Inbox listener) throws Exception {
        var ticket = tickets.issue(id, token);
        assertEquals(WebRtcTicketService.Outcome.ISSUED, ticket.outcome());
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3)).header("Origin", "http://localhost:5173")
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/webrtc/" + id
                + "?ticket=" + ticket.issued().value()), listener).get(5, TimeUnit.SECONDS);
    }

    private final class Inbox implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        private JsonNode next() throws Exception {
            String message = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(message, "expected a signaling message from real Tomcat");
            return json.readTree(message);
        }
    }
}
