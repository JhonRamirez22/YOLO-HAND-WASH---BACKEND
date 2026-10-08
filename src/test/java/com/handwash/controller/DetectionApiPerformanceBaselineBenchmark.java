package com.handwash.controller;

import tools.jackson.databind.ObjectMapper;
import com.handwash.model.EstadoSesion;
import com.handwash.model.PasoLavado;
import com.handwash.model.SesionLavado;
import com.handwash.model.TipoProtocolo;
import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in synthetic HTTP benchmark; not included in the default Surefire test patterns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:handwash-performance;DB_CLOSE_DELAY=-1",
    "handwash.notification.flush-ms=33"
})
class DetectionApiPerformanceBaselineBenchmark {
    private static final int REQUEST_SAMPLES = 500;
    private static final int CONFIRMATION_SAMPLES = 100;
    private static final int LOAD_WORKERS = 8;
    private static final int LOAD_REQUESTS_PER_WORKER = 200;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired SessionManager sessionManager;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void reportFourDecisionPathLatencyDistributions() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("environment", Map.of(
            "java", System.getProperty("java.version"),
            "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
            "architecture", System.getProperty("os.arch"),
            "processors", Runtime.getRuntime().availableProcessors(),
            "springBoot", org.springframework.boot.SpringBootVersion.getVersion(),
            "server", "embedded Tomcat, loopback, random port",
            "database", "H2 in-memory",
            "yolo", "not invoked",
            "media", "synthetic JSON metadata only"
        ));
        report.put("concurrency", 1);

        Session rejected = newSession();
        try {
            warmEndpoint(rejected, "PASO_1_PALMAS");
            List<Double> rejectedMs = new ArrayList<>(REQUEST_SAMPLES);
            for (int i = 1; i <= REQUEST_SAMPLES; i++) {
                TimedResponse response = post(rejected, PasoLavado.PASO_1_PALMAS.name(), 0.10,
                    null, i);
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"accepted\":false"));
                rejectedMs.add(response.elapsedMs());
            }
            report.put("event_rejected_confidence", distribution(rejectedMs));
        } finally {
            deleteSession(rejected);
        }

        Session repeatedFrame = newProducerSession();
        try {
            TimedResponse original = postTransportEnvelope(repeatedFrame, 1L);
            assertEquals(200, original.statusCode());
            assertTrue(original.body().contains("\"accepted\":true"));
            List<Double> duplicateMs = new ArrayList<>(REQUEST_SAMPLES);
            for (int i = 0; i < REQUEST_SAMPLES; i++) {
                TimedResponse duplicate = postTransportEnvelope(repeatedFrame, 1L);
                assertEquals(200, duplicate.statusCode());
                assertTrue(duplicate.body().contains("\"accepted\":false"));
                duplicateMs.add(duplicate.elapsedMs());
            }
            report.put("duplicate_v2_frame_early_rejection", distribution(duplicateMs));
        } finally {
            deleteSession(repeatedFrame);
        }

        Session active = newSession();
        try {
            confirmStart(List.of(active));
            List<Double> activeStepMs = new ArrayList<>(REQUEST_SAMPLES);
            long sequence = 4;
            for (int i = 0; i < REQUEST_SAMPLES; i++) {
                TimedResponse response = post(active, PasoLavado.PASO_1_PALMAS.name(), 0.95,
                    movement(sequence), sequence++);
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"accepted\":true"));
                activeStepMs.add(response.elapsedMs());
            }
            assertEquals(EstadoSesion.EN_PROGRESO,
                sessionManager.getSesion(active.id()).getEstadoSesion());
            report.put("valid_evidence_active_step", distribution(activeStepMs));
        } finally {
            deleteSession(active);
        }

        List<Session> startSessions = createSessions(CONFIRMATION_SAMPLES);
        try {
            report.put("start_confirmation", confirmStart(startSessions));
        } finally {
            startSessions.forEach(this::deleteSession);
        }

        List<Session> transitionSessions = createSessions(CONFIRMATION_SAMPLES);
        try {
            confirmStart(transitionSessions);
            sustainPalmsForRequiredProjectTime(transitionSessions);
            List<Double> transitionRequestMs = new ArrayList<>(CONFIRMATION_SAMPLES * 2);
            List<Double> transitionElapsedMs = new ArrayList<>(CONFIRMATION_SAMPLES);
            List<Long> firstCandidateNs = new ArrayList<>(CONFIRMATION_SAMPLES);
            for (Session session : transitionSessions) {
                firstCandidateNs.add(System.nanoTime());
                TimedResponse first = post(session, PasoLavado.PASO_2_DORSOS.name(), 0.95,
                    movement(10), 10);
                assertEquals(200, first.statusCode());
                transitionRequestMs.add(first.elapsedMs());
            }
            Thread.sleep(125L);
            for (int i = 0; i < transitionSessions.size(); i++) {
                Session session = transitionSessions.get(i);
                TimedResponse second = post(session, PasoLavado.PASO_2_DORSOS.name(), 0.95,
                    movement(11), 11);
                assertEquals(200, second.statusCode());
                transitionRequestMs.add(second.elapsedMs());
                transitionElapsedMs.add((System.nanoTime() - firstCandidateNs.get(i)) / 1_000_000.0);
                assertEquals(PasoLavado.PASO_2_DORSOS,
                    sessionManager.getSesion(session.id()).getEstadoActual().getPasoActual());
            }
            report.put("transition_confirmation_request", distribution(transitionRequestMs));
            report.put("transition_confirmation_elapsed", distribution(transitionElapsedMs));
        } finally {
            transitionSessions.forEach(this::deleteSession);
        }

        System.out.println("DETECTION_PERFORMANCE_BASELINE " + mapper.writeValueAsString(report));
    }

    @Test
    void reportConcurrentSingleAndMultipleSessionTransportLoad() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("environment", Map.of(
            "java", System.getProperty("java.version"),
            "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
            "architecture", System.getProperty("os.arch"),
            "processors", Runtime.getRuntime().availableProcessors(),
            "server", "embedded Tomcat, loopback, random port",
            "database", "H2 in-memory",
            "payload", "synthetic JSON metadata; epoch fields included",
            "yolo", "not invoked",
            "media", "none"
        ));

        List<Session> oneSession = List.of(newProducerSession());
        try {
            report.put("one_session_many_clients", concurrentLoad(oneSession, LOAD_WORKERS));
        } finally {
            oneSession.forEach(this::deleteSession);
        }

        List<Session> severalSessions = createProducerSessions(LOAD_WORKERS);
        try {
            report.put("eight_sessions_one_client_each", concurrentLoad(severalSessions, LOAD_WORKERS));
        } finally {
            severalSessions.forEach(this::deleteSession);
        }

        System.out.println("DETECTION_CONCURRENT_LOAD " + mapper.writeValueAsString(report));
    }

    private Map<String, Object> concurrentLoad(List<Session> sessions, int workers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong sharedSequence = new AtomicLong(0L);
        List<Future<WorkerResult>> futures = new ArrayList<>(workers);
        try {
            for (int worker = 0; worker < workers; worker++) {
                final int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    Session session = sessions.get(sessions.size() == 1 ? 0 : workerIndex);
                    List<Double> latency = new ArrayList<>(LOAD_REQUESTS_PER_WORKER);
                    int rejected = 0;
                    int non2xx = 0;
                    ready.countDown();
                    start.await();
                    for (int sample = 0; sample < LOAD_REQUESTS_PER_WORKER; sample++) {
                        long sequence = sessions.size() == 1
                            ? sharedSequence.incrementAndGet() : sample + 1L;
                        TimedResponse response = postTransportEnvelope(session, sequence);
                        latency.add(response.elapsedMs());
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            non2xx++;
                        } else if (response.body().contains("\"accepted\":false")) {
                            rejected++;
                        }
                    }
                    return new WorkerResult(latency, rejected, non2xx);
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "all synthetic clients must be ready before release");
            long started = System.nanoTime();
            start.countDown();
            List<Double> latencies = new ArrayList<>(workers * LOAD_REQUESTS_PER_WORKER);
            int rejected = 0;
            int non2xx = 0;
            for (Future<WorkerResult> future : futures) {
                WorkerResult result = future.get(REQUEST_TIMEOUT.toSeconds() * LOAD_REQUESTS_PER_WORKER,
                    java.util.concurrent.TimeUnit.SECONDS);
                latencies.addAll(result.latencyMs());
                rejected += result.transportRejected();
                non2xx += result.non2xx();
            }
            double wallSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
            return Map.of(
                "sessions", sessions.size(),
                "workers", workers,
                "requests", latencies.size(),
                "latency", distribution(latencies),
                "throughput_requests_per_second",
                    Math.round(latencies.size() / wallSeconds * 100.0) / 100.0,
                "transport_rejected_ack_count", rejected,
                "http_non_2xx_count", non2xx
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private TimedResponse postTransportEnvelope(Session session, long sequence) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", session.id());
        event.put("claseDetectada", PasoLavado.PASO_1_PALMAS.name());
        event.put("confianza", 0.95);
        event.put("timestamp", DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
            .format(Instant.now()));
        event.put("producerEpoch", session.producerEpoch());
        event.put("eventType", "DETECTION");
        event.put("frameSequence", sequence);
        event.put("captureAgeMs", 5);
        event.put("evidenciaMovimiento", movement(sequence));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/deteccion"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-Session-Token", session.token())
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event)))
            .build();
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new TimedResponse(response.statusCode(), response.body(),
            (System.nanoTime() - started) / 1_000_000.0);
    }

    private Map<String, Object> confirmStart(List<Session> sessions) throws Exception {
        List<Double> requestMs = new ArrayList<>(sessions.size() * 3);
        List<Long> firstPostNs = new ArrayList<>(sessions.size());
        for (Session session : sessions) {
            firstPostNs.add(System.nanoTime());
            TimedResponse response = post(session, PasoLavado.PASO_1_PALMAS.name(), 0.95,
                movement(1), 1);
            assertEquals(200, response.statusCode());
            requestMs.add(response.elapsedMs());
        }
        Thread.sleep(350L);
        for (Session session : sessions) {
            TimedResponse response = post(session, PasoLavado.PASO_1_PALMAS.name(), 0.95,
                movement(2), 2);
            assertEquals(200, response.statusCode());
            requestMs.add(response.elapsedMs());
        }
        Thread.sleep(350L);
        List<Double> confirmedMs = new ArrayList<>(sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            TimedResponse response = post(session, PasoLavado.PASO_1_PALMAS.name(), 0.95,
                movement(3), 3);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"accepted\":true"));
            requestMs.add(response.elapsedMs());
            confirmedMs.add((System.nanoTime() - firstPostNs.get(i)) / 1_000_000.0);
            assertEquals(PasoLavado.PASO_1_PALMAS,
                sessionManager.getSesion(session.id()).getEstadoActual().getPasoActual());
        }
        return Map.of(
            "request_samples", distribution(requestMs),
            "confirmation_wall_samples", distribution(confirmedMs),
            "sessions", sessions.size(),
            "observations_per_confirmation", 3,
            "configured_confirmation_ms", 650,
            "configured_max_gap_ms", 650
        );
    }

    private void sustainPalmsForRequiredProjectTime(List<Session> sessions) throws Exception {
        for (int round = 0; round < 6; round++) {
            Thread.sleep(1_000L);
            for (Session session : sessions) {
                long sequence = 4L + round;
                TimedResponse response = post(session, PasoLavado.PASO_1_PALMAS.name(), 0.95,
                    movement(sequence), sequence);
                assertEquals(200, response.statusCode());
            }
        }
    }

    private Session newSession() {
        String id = sessionManager.crearSesion(TipoProtocolo.DOMESTICO);
        return new Session(id, sessionManager.getOwnerToken(id), null);
    }

    private List<Session> createSessions(int count) {
        List<Session> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) sessions.add(newSession());
        return sessions;
    }

    private Session newProducerSession() {
        String id = sessionManager.crearSesion(TipoProtocolo.DOMESTICO, true);
        String token = sessionManager.getOwnerToken(id);
        SessionManager.ProducerEpochResult result = sessionManager.registrarProducerEpoch(id, token);
        if (result.outcome() != SessionManager.ProducerEpochOutcome.REGISTERED) {
            throw new IllegalStateException("Could not register benchmark producer epoch");
        }
        return new Session(id, token, result.registration().producerEpoch());
    }

    private List<Session> createProducerSessions(int count) {
        List<Session> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) sessions.add(newProducerSession());
        return sessions;
    }

    private void warmEndpoint(Session session, String step) throws Exception {
        for (int i = 0; i < 30; i++) {
            TimedResponse response = post(session, step, 0.10, null, i + 1);
            assertEquals(200, response.statusCode());
        }
    }

    private TimedResponse post(Session session, String step, double confidence,
                               Map<String, Object> movement, long sequence) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", session.id());
        event.put("claseDetectada", step);
        event.put("confianza", confidence);
        event.put("timestamp", DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
            .format(Instant.now()));
        if (movement != null) event.put("evidenciaMovimiento", movement);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/deteccion"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-Session-Token", session.token())
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event)))
            .build();
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new TimedResponse(response.statusCode(), response.body(),
            (System.nanoTime() - started) / 1_000_000.0);
    }

    private Map<String, Object> movement(long sequence) {
        return Map.of(
            "secuencia", sequence,
            "manosVisibles", 2,
            "movimientoNormalizado", 0.2,
            "medicionValida", true,
            "antiguedadMs", 100
        );
    }

    private void deleteSession(Session session) {
        sessionManager.eliminarSesion(session.id());
    }

    private Map<String, Object> distribution(List<Double> samples) {
        List<Double> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        return Map.of(
            "n", sorted.size(),
            "p50_ms", percentile(sorted, 0.50),
            "p95_ms", percentile(sorted, 0.95),
            "p99_ms", percentile(sorted, 0.99),
            "max_ms", sorted.get(sorted.size() - 1)
        );
    }

    private double percentile(List<Double> sorted, double quantile) {
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return Math.round(sorted.get(index) * 1000.0) / 1000.0;
    }

    private record Session(String id, String token, String producerEpoch) {}
    private record TimedResponse(int statusCode, String body, double elapsedMs) {}
    private record WorkerResult(List<Double> latencyMs, int transportRejected, int non2xx) {}
}
