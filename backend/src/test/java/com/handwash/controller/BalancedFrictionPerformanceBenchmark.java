package com.handwash.controller;

import com.handwash.model.*;
import com.handwash.service.SessionManager;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in A/B loopback benchmark: transport rejection, state snapshots, and significant decision persistence. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:balanced-benchmark;DB_CLOSE_DELAY=-1",
    "logging.level.root=ERROR"})
class BalancedFrictionPerformanceBenchmark {
    @Autowired SessionManager sessions;
    @Autowired ValidationRuleStrategyFactory factory;
    @Autowired ObjectMapper mapper;
    @Autowired com.handwash.service.SessionAuditService audit;
    @Autowired com.handwash.agent.SessionAuditObserver auditObserver;
    @LocalServerPort int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void compareUnderIdenticalSyntheticLoad() throws Exception {
        // Alternating rounds reduce warmup/order bias. This remains a small diagnostic, not an SLA.
        for(int round=0;round<4;round++) for(boolean balanced:round%2==0?new boolean[]{false,true}:new boolean[]{true,false}) {
            ReflectionTestUtils.setField(factory,"balancedFrictionEnabled",balanced);
            String id=sessions.crearSesion(ProtocolType.DOMESTICO);
            String token=sessions.getOwnerToken(id);
            audit.createEncounter(sessions.getSesion(id));
            auditObserver.registerSession(sessions.getSesion(id));
            try {
                List<Double> post=new ArrayList<>(), state=new ArrayList<>(), decisions=new ArrayList<>();
                for(int sample=0;sample<350;sample++) {
                    var body=mapper.writeValueAsString(Map.of("sessionId",id,"claseDetectada","PASO_1_PALMAS","confianza",0.1,"timestamp",Instant.now().toString()));
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/deteccion"))
                        .timeout(Duration.ofSeconds(3)).header("X-Session-Token",token).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    long start=System.nanoTime();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                    double elapsed=(System.nanoTime()-start)/1e6;
                    assertEquals(200,response.statusCode());
                    assertFalse(mapper.readTree(response.body()).path("accepted").asBoolean());
                    start=System.nanoTime();
                    mapper.writeValueAsString(sessions.getSesion(id).getEstadoActualResponse());
                    double snapshot=(System.nanoTime()-start)/1e6;
                    if(sample>=50) { post.add(elapsed);state.add(snapshot); }
                }
                // Same synthetic valid-step sequence in both policies; legacy may advance too early.
                // Includes State and (V2 only) significant-decision JDBC writes, excludes HTTP and YOLO.
                long base=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                for(int n=0;n<30;n++) {
                    var s=sessions.getSesion(id);
                    long start=System.nanoTime();
                    s.procesarDeteccionSinRespuesta(WashStep.PASO_1_PALMAS,base+n*250,0.95f);
                    sessions.notificarCambioEstado(s);
                    decisions.add((System.nanoTime()-start)/1e6);
                }
                if(round>0) System.out.println("BALANCED_AB "+mapper.writeValueAsString(Map.of(
                    "round",round,"policy",balanced?"FRICCION_BALANCEADA_V2":"LEGACY_V1",
                    "post_confidence_rejection_ms",percentiles(post),"snapshot_serialization_ms",percentiles(state),
                    "domain_and_persistence_ms",percentiles(decisions))));
            } finally { sessions.eliminarSesion(id); }
        }
        ReflectionTestUtils.setField(factory,"balancedFrictionEnabled",false);
    }
    private Map<String,Double> percentiles(List<Double> samples) {
        samples.sort(Double::compareTo);
        return Map.of("p50",samples.get((int)Math.ceil(samples.size()*0.50)-1),
            "p95",samples.get((int)Math.ceil(samples.size()*0.95)-1),
            "p99",samples.get((int)Math.ceil(samples.size()*0.99)-1));
    }
}
