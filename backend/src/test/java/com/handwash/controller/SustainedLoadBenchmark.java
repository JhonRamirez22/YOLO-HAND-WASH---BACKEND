package com.handwash.controller;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolType;
import com.handwash.service.SessionManager;
import com.handwash.service.audit.AuditChainVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in (not matched by Surefire): one station at the producer's real rate for a sustained period.
 * Latency is measured from the SCHEDULED send time, so a slow server cannot hide behind a slow client
 * (no coordinated omission). Afterwards the audit chain must still verify. Run with
 * {@code mvn -o test -Dtest=SustainedLoadBenchmark} and HANDWASH_LOAD_SECONDS / HANDWASH_LOAD_FPS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:handwash-sustained;DB_CLOSE_DELAY=-1",
    "handwash.notification.flush-ms=33"
})
class SustainedLoadBenchmark {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired SessionManager sessions;
    @Autowired AuditChainVerifier verifier;
    @Autowired com.handwash.service.SessionAuditService audit;
    @Autowired com.handwash.agent.SessionAuditObserver auditObserver;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void sustainedProducerRateKeepsLatencyAndAuditIntegrity() throws Exception {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("HANDWASH_LOAD_SECONDS", "60"));
        int fps = Integer.parseInt(System.getenv().getOrDefault("HANDWASH_LOAD_FPS", "15"));
        String id = sessions.crearSesion(ProtocolType.DOMESTICO);
        String token = sessions.getOwnerToken(id);
        audit.createEncounter(sessions.getSesion(id)); // as in production, so decisions are audited and chained
        auditObserver.registerSession(sessions.getSesion(id));
        List<WashStep> cycle = List.of(WashStep.PASO_1_PALMAS, WashStep.PASO_2_DORSOS, WashStep.PASO_3_INTERDIGITALES,
            WashStep.PASO_4_NUDILLOS, WashStep.PASO_5_PULGAR, WashStep.PASO_6_PUNTA_DE_DEDOS);
        long periodNs = 1_000_000_000L / fps;
        int total = seconds * fps;
        List<Double> latencyMs = new ArrayList<>(total);
        int non2xx = 0;
        var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcBefore = gcBeans.stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
        long maxHeap = 0;
        long start = System.nanoTime();
        try {
            for (int i = 0; i < total; i++) {
                long scheduled = start + i * periodNs;
                long wait = scheduled - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                WashStep step = cycle.get((i / (7 * fps)) % cycle.size()); // 7 s per movement, like the protocol
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("sessionId", id);
                event.put("claseDetectada", step.name());
                event.put("confianza", 0.95);
                event.put("timestamp", Instant.now().toString());
                event.put("evidenciaMovimiento", Map.of("secuencia", (long) i + 1, "manosVisibles", 2,
                    "movimientoNormalizado", 0.2, "medicionValida", true, "antiguedadMs", 100));
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/deteccion"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").header("X-Session-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event))).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) non2xx++;
                latencyMs.add((System.nanoTime() - scheduled) / 1_000_000.0);
                if (i % fps == 0) {
                    Runtime rt = Runtime.getRuntime();
                    maxHeap = Math.max(maxHeap, rt.totalMemory() - rt.freeMemory());
                }
            }
        } finally {
            sessions.eliminarSesion(id);
        }
        long wallMs = (System.nanoTime() - start) / 1_000_000L;
        long gcMs = gcBeans.stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum() - gcBefore;
        var chain = verifier.verify();
        List<Double> sorted = latencyMs.stream().sorted(Comparator.naturalOrder()).toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("seconds", seconds);
        report.put("fps", fps);
        report.put("requests", total);
        report.put("wall_ms", wallMs);
        report.put("non_2xx", non2xx);
        report.put("p50_ms", pct(sorted, 0.50));
        report.put("p95_ms", pct(sorted, 0.95));
        report.put("p99_ms", pct(sorted, 0.99));
        report.put("max_ms", sorted.get(sorted.size() - 1));
        report.put("gc_ms_total", gcMs);
        report.put("max_heap_mb", maxHeap / (1024 * 1024));
        report.put("audit_chain_ok", chain.ok());
        report.put("audit_events_checked", chain.checked());
        System.out.println("SUSTAINED_LOAD " + mapper.writeValueAsString(report));
        assertTrue(chain.ok(), "audit chain must stay consistent under sustained load: " + chain.problems());
    }

    private static double pct(List<Double> sorted, double q) {
        return Math.round(sorted.get(Math.max(0, (int) Math.ceil(q * sorted.size()) - 1)) * 1000.0) / 1000.0;
    }
}
