package com.handwash.service;

import com.handwash.agent.DetectionReceiver;
import com.handwash.model.DetectionEvent;
import com.handwash.model.SessionStatus;
import com.handwash.model.MovementEvidence;
import com.handwash.model.ProtocolType;
import com.handwash.recognition.ShadowSequenceComparison;
import com.handwash.recognition.ClassifierDistribution;
import com.handwash.recognition.ShadowSequenceSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionManagerSequenceShadowTest {
    private static final long MS = 1_000_000L;

    @Test
    void bilateralPresenceFeedsTheShadowDecoderWithoutTouchingOfficialState() {
        Fixture fixture = new Fixture(true);
        for (int frame = 0; frame < 3; frame++) {
            assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
                fixture.send(fixture.presence(frame, 2, palms(0.5)), frame * 200L).outcome());
        }

        ShadowSequenceSnapshot shadow = fixture.state().getDecodificadorSombra();
        assertEquals("PASO_1_PALMAS", shadow.pasoDecodificado());
        assertEquals(ShadowSequenceComparison.SOLO_DECODIFICADOR, shadow.comparacion());
        assertFalse(shadow.acreditaPasos());
        assertEquals(SessionStatus.ESPERANDO_INICIO, fixture.manager.getSesion(fixture.sessionId).getEstadoSesion());
        assertEquals(0, fixture.manager.getSesion(fixture.sessionId).getNumeroIntentoActual());
    }

    @Test
    void losingBothHandsClearsTheShadowContext() {
        Fixture fixture = new Fixture(true);
        fixture.send(fixture.presence(0, 2, palms(0.9)), 0);
        fixture.send(fixture.presence(1, 2, palms(0.9)), 200);
        fixture.send(fixture.presence(2, 1, null), 400);

        assertNull(fixture.state().getDecodificadorSombra());
    }

    @Test
    void transportRejectsTheDistributionOutsideBilateralPresence() {
        Fixture fixture = new Fixture(true);
        assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED,
            fixture.send(fixture.presence(0, 1, palms(0.9)), 0).outcome());

        DetectionEvent detection = fixture.detection(1);
        detection.setProbabilidadesClasificador(palms(0.9));
        assertEquals(SessionManager.DetectionOutcome.TRANSPORT_REJECTED, fixture.send(detection, 200).outcome());
    }

    @Test
    void disabledShadowAndMalformedDistributionsLeaveNoSnapshot() {
        Fixture disabled = new Fixture(false);
        disabled.send(disabled.presence(0, 2, palms(0.9)), 0);
        disabled.send(disabled.presence(1, 2, palms(0.9)), 200);
        assertNull(disabled.state().getDecodificadorSombra());

        Fixture malformed = new Fixture(true);
        Map<String, Double> wrong = palms(0.9);
        wrong.put("01_palm_to_palm", 5.0);
        assertEquals(SessionManager.DetectionOutcome.ACCEPTED,
            malformed.send(malformed.presence(0, 2, wrong), 0).outcome(),
            "a shadow-only defect must never block the presence gate");
        assertNull(malformed.state().getDecodificadorSombra());
    }

    private static Map<String, Double> palms(double peak) {
        Map<String, Double> wire = new LinkedHashMap<>();
        double rest = (1.0 - peak) / 8;
        for (int i = 0; i < ClassifierDistribution.CLASES.size(); i++) {
            wire.put(ClassifierDistribution.CLASES.get(i), i == 1 ? peak : rest);
        }
        return wire;
    }

    private static final class Fixture {
        final SessionManager manager = new SessionManager(new DetectionReceiver());
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final long start = clock.get();
        final String sessionId;
        final String token;
        final String epoch;

        Fixture(boolean shadowEnabled) {
            ReflectionTestUtils.setField(manager, "monotonicNanos", (LongSupplier) clock::get);
            ReflectionTestUtils.setField(manager, "sequenceDecoderShadowEnabled", shadowEnabled);
            sessionId = manager.crearSesion(ProtocolType.DOMESTICO, true);
            token = manager.getOwnerToken(sessionId);
            epoch = manager.registrarProducerEpoch(sessionId, token).registration().producerEpoch();
        }

        SessionManager.DetectionResult send(DetectionEvent event, long atMs) {
            long now = start + atMs * MS;
            clock.set(now);
            event.setServerIngressAtMonotonicNanos(now);
            return manager.procesarDeteccionHttp(event, token);
        }

        com.handwash.model.WashStateResponse state() {
            return manager.getSesion(sessionId).getEstadoActualResponse();
        }

        DetectionEvent presence(long sequence, int hands, Map<String, Double> probabilities) {
            DetectionEvent event = new DetectionEvent(sessionId, "PRESENCIA_MANOS", 1.0f, Instant.now().toString());
            event.setProducerEpoch(epoch);
            event.setEventType("PRESENCE");
            event.setControlSequence(sequence);
            event.setFrameWatermark(sequence);
            event.setCaptureAgeMs(0L);
            event.setPresenceHandsVisible(hands);
            event.setProbabilidadesClasificador(probabilities);
            return event;
        }

        DetectionEvent detection(long sequence) {
            DetectionEvent event = new DetectionEvent(sessionId, "PASO_1_PALMAS", 0.95f, Instant.now().toString());
            event.setProducerEpoch(epoch);
            event.setEventType("DETECTION");
            event.setFrameSequence(sequence);
            event.setCaptureAgeMs(0L);
            event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, 0.2, true, 0L));
            return event;
        }
    }
}
