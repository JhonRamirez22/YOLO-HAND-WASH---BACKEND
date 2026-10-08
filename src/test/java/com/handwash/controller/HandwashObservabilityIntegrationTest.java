package com.handwash.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.handwash.agent.Notifier;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "handwash.session.timeout-minutes=1",
    "handwash.session.expiration-check-ms=50",
    "handwash.notification.expired-summary-recovery-ms=60000"
})
@AutoConfigureTestRestTemplate
class HandwashObservabilityIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired SessionManager sessionManager;
    @Autowired Notifier notifier;
    @Autowired MeterRegistry meterRegistry;
    @LocalServerPort int port;

    @Test
    void expirationQueuesOneTerminalPairImmediatelyAndReconnectReplaysIt() throws Exception {
        String id = sessionManager.crearSesion(ProtocolType.DOMESTICO);
        String token = sessionManager.getOwnerToken(id);
        RecordingListener firstListener = new RecordingListener();
        WebSocket firstSocket = connect(id, token, firstListener);
        try {
            JsonNode initial = mapper.readTree(firstListener.messages.poll(3, TimeUnit.SECONDS));
            assertEquals("ESPERANDO_INICIO", initial.get("estadoSesion").asText());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Session-Token", token);
            ResponseEntity<String> accepted = http.exchange("/api/deteccion", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                    "sessionId", id,
                    "claseDetectada", "Paso1_Palmas",
                    "confianza", 0.95,
                    "timestamp", Instant.now().toString()
                ), headers), String.class);
            assertEquals(HttpStatus.OK, accepted.getStatusCode());
            assertTrue(mapper.readTree(accepted.getBody()).get("accepted").asBoolean());
            assertTrue(meterRegistry.counter("handwash.detection.intent.rejections",
                "reason", "sin_evidencia_reciente").count() > 0,
                "the observer should expose why an otherwise valid transport event did not qualify as handwash evidence");
            assertNotNull(firstListener.messages.poll(3, TimeUnit.SECONDS), "live state update");

            var session = sessionManager.getSesion(id);
            long inactivityDeadline = System.nanoTime();
            ReflectionTestUtils.setField(session, "ultimaActividadMonotonicNanos",
                System.nanoTime() - TimeUnit.MINUTES.toNanos(1));
            long detectionWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!session.getEstadoSesion().name().equals("EXPIRADA")
                && System.nanoTime() < detectionWaitDeadline) {
                Thread.sleep(1L);
            }
            double schedulerDetectionMs = (System.nanoTime() - inactivityDeadline) / 1_000_000.0;
            assertEquals("EXPIRADA", session.getEstadoSesion().name());
            assertTrue(schedulerDetectionMs < 500.0,
                "the test sweep is configured for 50 ms; detected " + schedulerDetectionMs + " ms");

            long terminalDeliveryStarted = System.nanoTime();
            JsonNode terminalState = mapper.readTree(firstListener.messages.poll(3, TimeUnit.SECONDS));
            JsonNode summary = mapper.readTree(firstListener.messages.poll(3, TimeUnit.SECONDS));
            double terminalDeliveryMs = (System.nanoTime() - terminalDeliveryStarted) / 1_000_000.0;
            assertEquals("EXPIRADA", terminalState.get("estadoSesion").asText());
            assertEquals("STATE_UPDATE", terminalState.get("messageType").asText());
            assertEquals("SESSION_SUMMARY", summary.get("messageType").asText());
            assertTrue(terminalDeliveryMs < 500.0,
                "delivery should use the state-change observer, not the 60 s recovery scan");

            notifier.enviarResumenesExpirados();
            assertNull(firstListener.messages.poll(150, TimeUnit.MILLISECONDS),
                "the periodic recovery scan must not duplicate the terminal pair");

            RecordingListener reconnectListener = new RecordingListener();
            WebSocket reconnect = connect(id, token, reconnectListener);
            try {
                JsonNode replayedState = mapper.readTree(reconnectListener.messages.poll(3, TimeUnit.SECONDS));
                JsonNode replayedSummary = mapper.readTree(reconnectListener.messages.poll(3, TimeUnit.SECONDS));
                assertEquals("EXPIRADA", replayedState.get("estadoSesion").asText());
                assertEquals("SESSION_SUMMARY", replayedSummary.get("messageType").asText());
            } finally {
                reconnect.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
            }

            assertEquals("EXPIRADA", session.getEstadoSesion().name());
            assertEquals(1.0, meterRegistry.counter("handwash.session.expirations",
                "cause", "idle_timeout").count(), "expiration metric must use the app registry");
            assertTrue(timerCount("handwash.detection.http.validation", "result", "valid") > 0);
            assertTrue(timerCount("handwash.detection.session.lock.wait") > 0);
            assertTrue(timerCount("handwash.detection.rules.state") > 0);
            assertTrue(timerCount("handwash.detection.rules.strategy") > 0);
            assertTrue(timerCount("handwash.detection.pipeline") > 0);
            assertTrue(timerCount("handwash.session.expiration.detection.delay") > 0);
            assertTrue(timerCount("handwash.websocket.pending.enqueue") > 0);
            assertTrue(timerCount("handwash.websocket.mailbox.enqueue") > 0);
            assertTrue(timerCount("handwash.websocket.send") > 0);
            assertTrue(timerCount("handwash.websocket.terminal.delivery") > 0);

            ResponseEntity<String> metricEndpoint = http.getForEntity(
                "/actuator/metrics/handwash.detection.rules.state", String.class);
            assertEquals(HttpStatus.OK, metricEndpoint.getStatusCode());
            assertFalse(mapper.readTree(metricEndpoint.getBody()).get("measurements").isEmpty());

            for (Meter meter : meterRegistry.getMeters()) {
                if (!meter.getId().getName().startsWith("handwash.")) continue;
                assertTrue(meter.getId().getTags().stream().noneMatch(tag ->
                    tag.getKey().equalsIgnoreCase("sessionId")
                        || tag.getKey().toLowerCase().contains("token")
                        || tag.getKey().toLowerCase().contains("frame")
                        || tag.getKey().toLowerCase().contains("sequence")));
            }
        } finally {
            try { firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join(); }
            catch (RuntimeException ignored) { firstSocket.abort(); }
            sessionManager.eliminarSesion(id);
            notifier.eliminarSesion(id);
        }
    }

    private long timerCount(String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        assertNotNull(timer, name);
        return timer.count();
    }

    private WebSocket connect(String id, String token, RecordingListener listener) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> ticketResponse = http.exchange(
            "/api/v1/session/" + id + "/websocket-ticket", HttpMethod.POST,
            new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, ticketResponse.getStatusCode());
        String ticket = mapper.readTree(ticketResponse.getBody()).get("ticket").asText();
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/" + id + "?ticket=" + ticket);
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, listener).join();
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
