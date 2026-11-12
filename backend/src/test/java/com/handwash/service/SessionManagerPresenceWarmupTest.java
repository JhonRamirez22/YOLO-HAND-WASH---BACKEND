package com.handwash.service;

import com.handwash.agent.DetectionReceiver;
import com.handwash.agent.SequenceEvaluator;
import com.handwash.intention.WashIntentionChain;
import com.handwash.model.DetectionEvent;
import com.handwash.model.SessionStatus;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandPoseEvidence;
import com.handwash.model.WashStep;
import com.handwash.model.ProtocolType;
import com.handwash.observer.DetectionObserver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerPresenceWarmupTest {
    private static final long MS = 1_000_000L;

    @Test
    void strictProducerCannotSendStepsUntilJavaSeesThreeContinuousSecondsOfTwoHands() {
        DetectionReceiver receptor = new DetectionReceiver();
        AtomicInteger forwardedSteps = new AtomicInteger();
        receptor.addObserver(new DetectionObserver() {
            @Override
            public void onDeteccion(DetectionEvent evento) {
                forwardedSteps.incrementAndGet();
            }
        });
        SessionManager manager = new SessionManager(receptor);
        AtomicLong clock = new AtomicLong(System.nanoTime());
        ReflectionTestUtils.setField(manager, "monotonicNanos", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 3_000L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String ownerToken = manager.getOwnerToken(sessionId);
        try {
            var epochRegistration = manager.registrarProducerEpoch(sessionId, ownerToken);
            assertEquals(SessionManager.ProducerEpochOutcome.REGISTERED, epochRegistration.outcome());
            String epoch = epochRegistration.registration().producerEpoch();
            long beganAt = clock.get();

            for (int observation = 0; observation <= 5; observation++) {
                long receivedAt = beganAt + observation * 500L * MS;
                clock.set(receivedAt);
                assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                    manager.procesarDeteccionHttp(presence(
                        sessionId, epoch, observation, observation, 2, receivedAt), ownerToken).outcome());
            }
            assertEquals(SessionStatus.ESPERANDO_INICIO,
                manager.getSesion(sessionId).getEstadoSesion(),
                "la presencia bilateral estable solo habilita detección; no inicia el lavado");
            assertEquals(0, manager.getSesion(sessionId).getNumeroIntentoActual(),
                "no debe contabilizarse un intento sin gesto de inicio");

            long beforeReady = beganAt + 2_500L * MS;
            clock.set(beforeReady);
            manager.procesarDeteccionHttp(step(sessionId, epoch, 6, beforeReady), ownerToken);
            assertEquals(0, forwardedSteps.get(), "premature phase evidence must not reach observers");
            String gateReason = manager.getSesion(sessionId).getEstadoActualResponse().getMotivoIntencion();
            assertTrue(gateReason.contains("no confirma 3000 ms"));

            long readyAt = beganAt + 3_000L * MS;
            clock.set(readyAt);
            manager.procesarDeteccionHttp(
                presence(sessionId, epoch, 6, 7, 2, readyAt), ownerToken);
            assertEquals(SessionManager.DetectionOutcome.FILTERED,
                manager.procesarDeteccionHttp(step(sessionId, epoch, 8, readyAt), ownerToken).outcome(),
                "la primera pose después del calentamiento solo establece la base temporal");
            long rubbingAt = readyAt + 200L * MS;
            clock.set(rubbingAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(step(sessionId, epoch, 9, rubbingAt), ownerToken).outcome());
            assertEquals(1, forwardedSteps.get(), "a phase may enter the pipeline after the server dwell");

            long stalePresenceAt = beganAt + 3_501L * MS;
            clock.set(stalePresenceAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(
                    step(sessionId, epoch, 10, stalePresenceAt), ownerToken).outcome());
            assertEquals(1, forwardedSteps.get(),
                "a step with expired bilateral presence must not reach observers");
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void delayedStepFromBeforePresenceWatermarkCannotReachObserversAfterWarmup() {
        DetectionReceiver receptor = new DetectionReceiver();
        AtomicInteger forwardedSteps = new AtomicInteger();
        receptor.addObserver(new DetectionObserver() {
            @Override
            public void onDeteccion(DetectionEvent evento) {
                forwardedSteps.incrementAndGet();
            }
        });
        SessionManager manager = new SessionManager(receptor);
        AtomicLong clock = new AtomicLong(System.nanoTime());
        ReflectionTestUtils.setField(manager, "monotonicNanos", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 3_000L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String ownerToken = manager.getOwnerToken(sessionId);
        try {
            var registration = manager.registrarProducerEpoch(sessionId, ownerToken);
            String epoch = registration.registration().producerEpoch();
            long beganAt = clock.get();
            for (int observation = 0; observation <= 6; observation++) {
                long receivedAt = beganAt + observation * 500L * MS;
                clock.set(receivedAt);
                manager.procesarDeteccionHttp(
                    presence(sessionId, epoch, observation, observation, 2, receivedAt), ownerToken);
            }

            long afterWarmup = beganAt + 3_000L * MS;
            clock.set(afterWarmup);
            var delayed = manager.procesarDeteccionHttp(
                step(sessionId, epoch, 5, afterWarmup), ownerToken);
            assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED, delayed.outcome());
            assertEquals(0, forwardedSteps.get(),
                "a frame older than the latest bilateral-presence watermark cannot enter the pipeline");

            var baseline = manager.procesarDeteccionHttp(
                step(sessionId, epoch, 7, afterWarmup), ownerToken);
            assertEquals(SessionManager.DetectionOutcome.FILTERED, baseline.outcome());
            long rubbingAt = afterWarmup + 200L * MS;
            clock.set(rubbingAt);
            var current = manager.procesarDeteccionHttp(
                step(sessionId, epoch, 8, rubbingAt), ownerToken);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED, current.outcome());
            assertEquals(1, forwardedSteps.get());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void bilateralPresenceKeepsSessionAliveWithoutStartingOrCreditingTheWash() {
        SessionManager manager = new SessionManager(new DetectionReceiver());
        AtomicLong clock = new AtomicLong(System.nanoTime());
        ReflectionTestUtils.setField(manager, "monotonicNanos", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 3_000L);
        ReflectionTestUtils.setField(manager, "timeoutMinutes", 1L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String ownerToken = manager.getOwnerToken(sessionId);
        try {
            String epoch = manager.registrarProducerEpoch(sessionId, ownerToken)
                .registration().producerEpoch();
            long start = clock.get();
            long bilateralAt = start + 59_000L * MS;
            clock.set(bilateralAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(
                    presence(sessionId, epoch, 0, 1, 2, bilateralAt), ownerToken).outcome());

            var session = manager.getSesion(sessionId);
            assertTrue(session.getTiempoInactivoMs(System.currentTimeMillis(), clock.get()) < 1_000L,
                "authenticated bilateral presence refreshes only the idle-session clock");
            assertEquals(SessionStatus.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(0, session.getNumeroIntentoActual());

            long oneHandAt = bilateralAt + 1_000L * MS;
            clock.set(oneHandAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(
                    presence(sessionId, epoch, 1, 2, 1, oneHandAt), ownerToken).outcome());
            assertTrue(session.getTiempoInactivoMs(System.currentTimeMillis(), clock.get()) >= 1_000L,
                "one-hand presence must not keep an unstarted session alive");
            assertEquals(SessionStatus.ESPERANDO_INICIO, session.getEstadoSesion());
            assertEquals(0, session.getNumeroIntentoActual());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    @Test
    void backgroundControlPausesOnFreshBilateralHandsButResetsAfterHandsDisappear() {
        DetectionReceiver receptor = new DetectionReceiver();
        SessionManager manager = new SessionManager(receptor);
        AtomicLong clock = new AtomicLong(System.nanoTime());
        ReflectionTestUtils.setField(manager, "monotonicNanos", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(manager, "handPresenceWarmupMs", 3_000L);
        String sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
        String ownerToken = manager.getOwnerToken(sessionId);
        receptor.addObserver(new SequenceEvaluator(manager,
            new WashIntentionChain(650, 650, 3, 1_500, 0.75)));
        try {
            String epoch = manager.registrarProducerEpoch(sessionId, ownerToken)
                .registration().producerEpoch();
            var session = manager.getSesion(sessionId);
            session.procesarDeteccion(WashStep.PASO_1_PALMAS, 1_000L, 0.95f);
            assertEquals(SessionStatus.EN_PROGRESO, session.getEstadoSesion());
            int activeAttempt = session.getNumeroIntentoActual();

            long start = clock.get();
            for (int observation = 0; observation <= 6; observation++) {
                long receivedAt = start + observation * 500L * MS;
                clock.set(receivedAt);
                assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                    manager.procesarDeteccionHttp(presence(sessionId, epoch,
                        observation, observation, 2, receivedAt), ownerToken).outcome());
            }

            long abstentionAt = start + 3_100L * MS;
            clock.set(abstentionAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(backgroundControl(
                    sessionId, epoch, 7, 6, abstentionAt), ownerToken).outcome());
            assertEquals(SessionStatus.EN_PROGRESO, session.getEstadoSesion(),
                "classifier abstention with fresh bilateral presence must not reset State");
            assertEquals(activeAttempt, session.getNumeroIntentoActual());
            assertEquals(0, session.getIntentosReiniciados());
            assertEquals("PAUSA", session.getEstadoActualResponse().getEstadoIntencion());

            long oneHandAt = start + 3_200L * MS;
            clock.set(oneHandAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(presence(sessionId, epoch,
                    8, 7, 1, oneHandAt), ownerToken).outcome());
            long lostAt = start + 3_300L * MS;
            clock.set(lostAt);
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                manager.procesarDeteccionHttp(backgroundControl(
                    sessionId, epoch, 9, 7, lostAt), ownerToken).outcome());
            assertEquals(SessionStatus.ESPERANDO_INICIO, session.getEstadoSesion(),
                "a background control without fresh bilateral presence must retain reset behavior");
            assertEquals(1, session.getIntentosReiniciados());
        } finally {
            manager.eliminarSesion(sessionId);
        }
    }

    private DetectionEvent presence(String sessionId, String epoch, long controlSequence,
                                    long frameWatermark, int hands, long ingressAt) {
        DetectionEvent event = new DetectionEvent(
            sessionId, "PRESENCIA_MANOS", 1.0f, Instant.now().toString());
        event.setProducerEpoch(epoch);
        event.setEventType("PRESENCE");
        event.setControlSequence(controlSequence);
        event.setFrameWatermark(frameWatermark);
        event.setCaptureAgeMs(0L);
        event.setPresenceHandsVisible(hands);
        event.setServerIngressAtMonotonicNanos(ingressAt);
        return event;
    }

    private DetectionEvent step(String sessionId, String epoch, long sequence, long ingressAt) {
        DetectionEvent event = new DetectionEvent(
            sessionId, "PASO_1_PALMAS", 0.95f, Instant.now().toString());
        event.setProducerEpoch(epoch);
        event.setEventType("DETECTION");
        event.setFrameSequence(sequence);
        event.setCaptureAgeMs(0L);
        event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, 0.2, true, 0L));
        event.setEvidenciaPoseManos(pose(sequence));
        event.setServerIngressAtMonotonicNanos(ingressAt);
        return event;
    }

    private DetectionEvent backgroundControl(String sessionId, String epoch,
                                              long controlSequence, long frameWatermark,
                                              long ingressAt) {
        DetectionEvent event = new DetectionEvent(
            sessionId, "FONDO", 1.0f, Instant.now().toString());
        event.setProducerEpoch(epoch);
        event.setEventType("CONTROL");
        event.setControlSequence(controlSequence);
        event.setFrameWatermark(frameWatermark);
        event.setCaptureAgeMs(0L);
        event.setServerIngressAtMonotonicNanos(ingressAt);
        return event;
    }

    private HandPoseEvidence pose(long sequence) {
        Double[][][] points = new Double[2][21][3];
        Double[][] boxes = new Double[2][4];
        double shift = 2.0 * Math.sin(sequence * Math.PI / 4.0);
        for (int hand = 0; hand < 2; hand++) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int point = 0; point < 21; point++) {
                double x = 100 + (point % 5) * 10 + hand * 65;
                double y = 100 + (point / 5) * 10 + (hand == 0 ? shift : 0.0);
                points[hand][point] = new Double[] {x, y, 0.95};
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            boxes[hand] = new Double[] {minX - 4, minY - 4, maxX + 4, maxY + 4};
        }
        return new HandPoseEvidence(points, boxes, 640, 480);
    }
}
