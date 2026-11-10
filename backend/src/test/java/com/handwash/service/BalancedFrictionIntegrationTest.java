package com.handwash.service;

import com.handwash.model.*;
import com.handwash.strategy.BalancedFrictionStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic synthetic anatomy exercises the actual v2/OpenCV/Observer pipeline, not recognition accuracy. */
@SpringBootTest(properties={"handwash.validation.balanced-friction-enabled=true",
    "spring.datasource.url=jdbc:h2:mem:balanced-integration;DB_CLOSE_DELAY=-1"})
class BalancedFrictionIntegrationTest {
    @Autowired SessionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.handwash.agent.DetectionReceiver receptor;
    @Autowired SessionAuditService audit;
    @Autowired com.handwash.agent.SessionAuditObserver auditObserver;
    private long frame;
    private long control;
    private String id;
    private String token;
    private String epoch;
    private final AtomicLong clock = new AtomicLong(System.nanoTime());

    @Test void replaySixStepsPersistsFinalAndCyclesWithoutReplacingTechnicalSession() throws Exception {
        ReflectionTestUtils.setField(manager,"monotonicNanos",(LongSupplier)clock::get);
        ReflectionTestUtils.setField(receptor,"monotonicNanos",(LongSupplier)clock::get);
        id=manager.crearSesion(ProtocolType.DOMESTICO,true);
        token=manager.getOwnerToken(id);
        audit.createEncounter(manager.getSesion(id));
        auditObserver.registerSession(manager.getSesion(id));
        epoch=manager.registrarProducerEpoch(id,token).registration().producerEpoch();
        try {
            for(int i=0;i<14;i++) { presence(2); tick(); }
            var session=manager.getSesion(id);
            assertEquals(BalancedFrictionStrategy.VERSION,session.getEstrategia().getVersionPolitica());
            for(var step:BalancedFrictionStrategy.STEPS) {
                for(int i=0;i<52;i++) {
                    presence(2);
                    var event=detection(step);
                    manager.procesarDeteccionHttp(event,token);
                    tick();
                }
            }
            var state=session.getEstadoActualResponse();
            assertEquals(6,state.getProgreso().getPasosCompletados(), () -> mapper.writeValueAsString(state));
            assertTrue(state.getEvaluacionFriccion().secuenciaValidada());
            assertFalse(state.isProcedimientoCompletoValidado());
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM friction_step_validation WHERE session_id=? AND status='VALIDADO'",Integer.class,id));
            assertEquals("FRICTION_SEQUENCE_COMPLETE",jdbc.queryForObject("SELECT result FROM friction_attempt_validation WHERE session_id=?",String.class,id));
            assertEquals("CORRECTO",jdbc.queryForObject("SELECT outcome FROM friction_attempt_validation WHERE session_id=?",String.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation WHERE session_id=? AND started_at_epoch_ms IS NOT NULL AND finished_at_epoch_ms >= started_at_epoch_ms AND duration_ms > 0 AND evidence_status IS NULL",Integer.class,id));
            var http=new com.handwash.api.v1.mapper.SessionResponseMapper().toEvaluation(state);
            assertEquals(state.getEvaluacionFriccion(),http.evaluacionFriccion());
            var ws=mapper.readTree(mapper.writeValueAsString(state));
            assertEquals(6,ws.path("evaluacionFriccion").path("pasos").size());
            assertFalse(ws.path("procedimientoCompletoValidado").asBoolean());
            long revision=state.getEvaluacionFriccion().revisionEstado();
            var duplicate=detection(WashStep.PASO_1_PALMAS);
            manager.procesarDeteccionHttp(duplicate,token);
            long after= session.getEstadoActualResponse().getEvaluacionFriccion().revisionEstado();
            assertTrue(after>=revision);
            assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED,manager.procesarDeteccionHttp(duplicate,token).outcome());
            assertEquals(after,session.getEstadoActualResponse().getEvaluacionFriccion().revisionEstado());
            for(int i=0;i<=12;i++) { presence(0); tick(); }
            assertEquals(2,session.getNumeroIntentoActual());
            assertEquals(0,session.getPasosCompletados());
            assertEquals(id,session.getSessionId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation WHERE session_id=?",Integer.class,id));
            var old=detection(WashStep.PASO_1_PALMAS);
            manager.registrarProducerEpoch(id,token);
            assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED,manager.procesarDeteccionHttp(old,token).outcome());
            assertEquals(0,session.getPasosCompletados());
        } finally {
            manager.eliminarSesion(id);
            ReflectionTestUtils.setField(manager,"monotonicNanos",(LongSupplier)System::nanoTime);
            ReflectionTestUtils.setField(receptor,"monotonicNanos",(LongSupplier)System::nanoTime);
        }
    }

    @Test void finalStorageFailureRetriesIdempotentlyWithoutPublishingDurableCompletion() {
        String sessionId=manager.crearSesion(ProtocolType.DOMESTICO,true);
        var session=manager.getSesion(sessionId);
        audit.createEncounter(session);
        auditObserver.registerSession(session);
        var original=(com.handwash.repository.FrictionAttemptRepository)ReflectionTestUtils.getField(manager,"frictionRepository");
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        var failing=new com.handwash.repository.FrictionAttemptRepository(jdbc) {
            @Override public void save(FrictionAttemptRecord record) {
                if(record.terminal() && fail.get()) throw new org.springframework.dao.DataAccessResourceFailureException("synthetic outage");
                super.save(record);
            }
        };
        ReflectionTestUtils.setField(manager,"frictionRepository",failing);
        var alerts=new com.handwash.ops.LogCapture(SessionManager.class);
        try {
            long now=1000;
            for(var step:BalancedFrictionStrategy.STEPS) {
                session.procesarDeteccionSinRespuesta(step,now+=200,0.95f);
                session.procesarDeteccionSinRespuesta(step,now+=200,0.95f);
                for(int i=0;i<8;i++) {
                    session.procesarDeteccionSinRespuesta(step,now+=1000,0.95f);
                    manager.notificarCambioEstado(session);
                }
            }
            assertEquals("PENDIENTE_PERSISTENCIA",session.getEstadoActualResponse().getEvaluacionFriccion().estadoIntento());
            assertFalse(session.isSecuenciaFriccionObservadaCompleta());
            assertTrue(alerts.alerts(com.handwash.ops.OpsAlert.Code.FRICTION_PERSISTENCE_FAILED) >= 1,
                "a storage outage must raise the alarm token");
            fail.set(false);
            manager.persistirIntentosFallidosPendientes();
            manager.persistirIntentosFallidosPendientes();
            assertTrue(session.isSecuenciaFriccionObservadaCompleta());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation WHERE session_id=? AND terminal=TRUE",Integer.class,sessionId));
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM friction_step_validation WHERE session_id=?",Integer.class,sessionId));
        } finally {
            alerts.close();
            ReflectionTestUtils.setField(manager,"frictionRepository",original);
            manager.eliminarSesion(sessionId);
        }
    }
    /** R-01: an outage of the photo settings must not escape the frame path nor lose the reported wash. */
    @Test void photoSettingsOutageDuringAReportDefersPersistenceInsteadOfThrowing() {
        ReflectionTestUtils.setField(manager,"monotonicNanos",(LongSupplier)clock::get);
        ReflectionTestUtils.setField(receptor,"monotonicNanos",(LongSupplier)clock::get);
        id=manager.crearSesion(ProtocolType.DOMESTICO,true);
        token=manager.getOwnerToken(id);
        audit.createEncounter(manager.getSesion(id));
        auditObserver.registerSession(manager.getSesion(id));
        epoch=manager.registrarProducerEpoch(id,token).registration().producerEpoch();
        var original=ReflectionTestUtils.getField(manager,"evidenciaReporte");
        var down=new java.util.concurrent.atomic.AtomicBoolean(true);
        ReflectionTestUtils.setField(manager,"evidenciaReporte",(com.handwash.service.evidence.ReportEvidencePort)(sid,attemptNumber)->{
            if(down.get()) throw new org.springframework.dao.DataAccessResourceFailureException("settings outage");
            return com.handwash.service.evidence.EvidenceStatus.DESACTIVADA;
        });
        try {
            for(int i=0;i<14;i++) { presence(2); tick(); }
            for(int i=0;i<20;i++) {
                presence(2);
                manager.procesarDeteccionHttp(detection(WashStep.PASO_1_PALMAS),token);
                tick();
            }
            assertDoesNotThrow(() -> { for(int i=0;i<=44;i++) { presence(0); tick(); } });
            assertEquals("PENDIENTE_PERSISTENCIA",manager.getSesion(id).getEstadoActualResponse().getEvaluacionFriccion().estadoIntento());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM friction_attempt_validation WHERE session_id=? AND terminal=TRUE",Integer.class,id));
            down.set(false);
            manager.persistirIntentosFallidosPendientes();
            manager.notificarCambioEstado(manager.getSesion(id));
            assertEquals("REPORTADO",jdbc.queryForObject("SELECT outcome FROM friction_attempt_validation WHERE session_id=? AND attempt_number=1",String.class,id));
        } finally {
            ReflectionTestUtils.setField(manager,"evidenciaReporte",original);
            manager.eliminarSesion(id);
        }
    }

    private void tick(){ clock.addAndGet(250_000_000L); }
    @Test void leavingAnUnfinishedAttemptIsReportedWithPhotoCaptureDisabled() {
        ReflectionTestUtils.setField(manager,"monotonicNanos",(LongSupplier)clock::get);
        ReflectionTestUtils.setField(receptor,"monotonicNanos",(LongSupplier)clock::get);
        id=manager.crearSesion(ProtocolType.DOMESTICO,true);
        token=manager.getOwnerToken(id);
        audit.createEncounter(manager.getSesion(id));
        auditObserver.registerSession(manager.getSesion(id));
        epoch=manager.registrarProducerEpoch(id,token).registration().producerEpoch();
        try {
            for(int i=0;i<14;i++) { presence(2); tick(); }
            for(int i=0;i<20;i++) {
                presence(2);
                manager.procesarDeteccionHttp(detection(WashStep.PASO_1_PALMAS),token);
                tick();
            }
            for(int i=0;i<=44;i++) { presence(0); tick(); }
            assertEquals("REPORTADO",jdbc.queryForObject("SELECT outcome FROM friction_attempt_validation WHERE session_id=? AND attempt_number=1",String.class,id));
            assertEquals("DESACTIVADA",jdbc.queryForObject("SELECT evidence_status FROM friction_attempt_validation WHERE session_id=? AND attempt_number=1",String.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wash_report_evidence WHERE session_id=?",Integer.class,id),
                "no image reference exists while capture is disabled");
        } finally {
            manager.eliminarSesion(id);
        }
    }
    @Test void experimentalPolicyCannotRunInEitherStationProfile() {
        var factory=new com.handwash.strategy.ValidationRuleStrategyFactory();
        ReflectionTestUtils.setField(factory,"balancedFrictionEnabled",true);
        for(String profile:new String[]{"station","station-demo"}) {
            var env=new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);
            var local=new SessionManager(new com.handwash.agent.DetectionReceiver(),factory);
            ReflectionTestUtils.setField(local,"environment",env);
            assertThrows(IllegalStateException.class,local::validarConfiguracionRuntime);
        }
    }
    @Test void authenticatedEpochRotationAllowsLowFrameSequencesWithoutErasingValidatedSteps() {
        var strategy=new BalancedFrictionStrategy(new com.handwash.strategy.DomesticStrategy());
        var session=new WashSession("epoch-test",ProtocolType.DOMESTICO,strategy);
        for(long now=1000;now<=7000;now+=1000) session.procesarDeteccionSinRespuesta(WashStep.PASO_1_PALMAS,now,0.95f);
        var chain=new com.handwash.intention.WashIntentionChain(650,650,3,1500,0.75);
        var first=new DetectionEvent("epoch-test","PASO_1_PALMAS",0.95f,Instant.now().toString());
        first.setEvidenciaMovimiento(new MovementEvidence(100L,2,0.2,true,0L));
        first.setServerReceivedAtMonotonicMs(8000);
        assertTrue(session.evaluarIntencion(first,chain));
        session.frictionProducerReplaced();
        var replacement=new DetectionEvent("epoch-test","PASO_1_PALMAS",0.95f,Instant.now().toString());
        replacement.setEvidenciaMovimiento(new MovementEvidence(1L,2,0.2,true,0L));
        replacement.setServerReceivedAtMonotonicMs(8250);
        assertTrue(session.evaluarIntencion(replacement,chain));
        assertEquals(1,session.getPasosCompletados());
    }
    @Test void closingAnActiveAttemptPersistsInterruptionInsteadOfDeletingItsAudit() {
        String sessionId=manager.crearSesion(ProtocolType.DOMESTICO,true);
        var session=manager.getSesion(sessionId);
        audit.createEncounter(session);
        auditObserver.registerSession(session);
        session.procesarDeteccionSinRespuesta(WashStep.PASO_1_PALMAS,1000,0.95f);
        assertTrue(manager.eliminarSesion(sessionId));
        assertNull(manager.getSesion(sessionId));
        assertEquals("SESSION_CLOSED",jdbc.queryForObject("SELECT reason FROM friction_attempt_validation WHERE session_id=?",String.class,sessionId));
        assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT result FROM friction_attempt_validation WHERE session_id=?",String.class,sessionId));
    }
    private void presence(int hands){
        var event=new DetectionEvent(id,"PRESENCIA_MANOS",1f,Instant.now().toString());
        event.setProducerEpoch(epoch); event.setEventType("PRESENCE");
        event.setControlSequence(++control); event.setFrameWatermark(++frame);
        event.setPresenceHandsVisible(hands); event.setCaptureAgeMs(0L);
        event.setServerIngressAtMonotonicNanos(clock.get());
        assertEquals(SessionManager.DetectionOutcome.ACCEPTED,manager.procesarDeteccionHttp(event,token).outcome());
    }
    private DetectionEvent detection(WashStep step){
        long sequence=++frame;
        var event=new DetectionEvent(id,step.name(),0.95f,Instant.now().toString());
        event.setProducerEpoch(epoch); event.setEventType("DETECTION"); event.setFrameSequence(sequence);
        event.setCaptureAgeMs(0L); event.setServerIngressAtMonotonicNanos(clock.get());
        event.setEvidenciaMovimiento(new MovementEvidence(sequence,2,0.2,true,0L));
        Double[][][] points=new Double[2][21][3]; Double[][] boxes=new Double[2][4];
        double shift=2*Math.sin(sequence*Math.PI/4);
        for(int hand=0;hand<2;hand++){
            double x0=100+hand*65, y0=100+(hand==0?shift:0);
            for(int p=0;p<21;p++) points[hand][p]=new Double[]{x0+(p%5)*10,y0+(p/5)*10,0.95};
            boxes[hand]=new Double[]{x0-4,y0-4,x0+44,y0+44};
        }
        event.setEvidenciaPoseManos(new HandPoseEvidence(points,boxes,640,480));
        return event;
    }
}
