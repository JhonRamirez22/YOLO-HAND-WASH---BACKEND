package com.handwash.service;

import com.handwash.model.DeteccionEvento;
import com.handwash.model.EvidenciaJabon;
import com.handwash.model.EstadoEvidenciaJabon;
import com.handwash.model.AccionOms;
import com.handwash.model.PasoLavado;
import com.handwash.service.HandwashMetrics.ProducerRejectionReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;

class ProducerProtocolRegistryTest {
    private static final String SESSION = "session-a";

    @Test
    void reservesOnlyStrictlyIncreasingFramesAndReportsMatchingEpochAgeAsDiagnostic() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        var first = registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 8L, 21L));
        assertTrue(first.acceptedEnvelope());
        assertEquals(21L, first.matchingCaptureAgeMs());

        var duplicate = registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 8L, 22L));
        assertEquals(ProducerRejectionReason.FRAME_SEQUENCE_DUPLICATE,
            duplicate.rejectionReason());
        assertEquals(22L, duplicate.matchingCaptureAgeMs());

        var outOfOrder = registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 7L, 23L));
        assertEquals(ProducerRejectionReason.FRAME_SEQUENCE_OUT_OF_ORDER,
            outOfOrder.rejectionReason());

        assertTrue(registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 9L, null)).acceptedEnvelope());
    }

    @Test
    void strictV2AcceptsOnlyExactCanonicalDetectionLabelsAndDoesNotConsumeRejectedFrame() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        DeteccionEvento legacyAlias = detection(registration.producerEpoch(), 17L, null);
        legacyAlias.setClaseDetectada("Paso1_Palmas");
        assertEquals(ProducerRejectionReason.NON_CANONICAL_CLASS,
            registry.assessAndReserve(SESSION, legacyAlias).rejectionReason());

        DeteccionEvento semanticAlias = detection(registration.producerEpoch(), 17L, null);
        semanticAlias.setClaseDetectada("Paso3_PalmaDorsoDedos");
        assertEquals(ProducerRejectionReason.NON_CANONICAL_CLASS,
            registry.assessAndReserve(SESSION, semanticAlias).rejectionReason());

        DeteccionEvento corrected = detection(registration.producerEpoch(), 17L, null);
        assertTrue(registry.assessAndReserve(SESSION, corrected).acceptedEnvelope(),
            "the producer may retry the unconsumed frame using its exact canonical class");

        long sequence = 18L;
        for (PasoLavado step : PasoLavado.values()) {
            if (step == PasoLavado.FONDO) continue;
            DeteccionEvento event = detection(registration.producerEpoch(), sequence++, null);
            event.setClaseDetectada(step.name());
            assertTrue(registry.assessAndReserve(SESSION, event).acceptedEnvelope(), step.name());
        }
        for (AccionOms action : AccionOms.values()) {
            if (action.esSinEvidencia()) continue;
            DeteccionEvento event = detection(registration.producerEpoch(), sequence++, null);
            event.setClaseDetectada(action.getClaseModelo());
            assertTrue(registry.assessAndReserve(SESSION, event).acceptedEnvelope(),
                action.getClaseModelo());
        }

        for (String controlOnlyLabel : new String[] {"FONDO", "OMS_SIN_EVIDENCIA"}) {
            DeteccionEvento controlOnly = detection(registration.producerEpoch(), sequence, null);
            controlOnly.setClaseDetectada(controlOnlyLabel);
            assertEquals(ProducerRejectionReason.NON_CANONICAL_CLASS,
                registry.assessAndReserve(SESSION, controlOnly).rejectionReason());
        }
    }

    @Test
    void validProducerEnvelopeConsumesSequencesBeforeDomainValidation() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        assertTrue(registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 500L, null)).acceptedEnvelope());
        assertEquals(ProducerRejectionReason.FRAME_SEQUENCE_DUPLICATE,
            registry.assessAndReserve(SESSION,
                detection(registration.producerEpoch(), 500L, null)).rejectionReason(),
            "a domain-invalid observation cannot be repaired and replayed as the same camera frame");

        DeteccionEvento invalidControl = control(registration.producerEpoch(), 20L, 900L);
        assertTrue(registry.assessAndReserve(SESSION, invalidControl).acceptedEnvelope());

        DeteccionEvento validFrame = detection(registration.producerEpoch(), 901L, null);
        assertTrue(registry.assessAndReserve(SESSION, validFrame).acceptedEnvelope(),
            "control watermarks and frame watermarks are independent while preserving ordering");
        assertEquals(ProducerRejectionReason.CONTROL_SEQUENCE_DUPLICATE,
            registry.assessAndReserve(SESSION,
                control(registration.producerEpoch(), 20L, 901L)).rejectionReason(),
            "a valid control envelope consumes its control sequence before downstream validation");
    }

    @Test
    void soapEvidenceMustBelongToTheDetectionFrameBeforeItsWatermarkIsReserved() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        DeteccionEvento staleSoap = detection(registration.producerEpoch(), 41L, null);
        staleSoap.setEvidenciaJabon(Map.of("PALMA_IZQUIERDA",
            new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f)));
        staleSoap.setEvidenciaJabonSecuencia(40L);
        assertEquals(ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_MISMATCH,
            registry.assessAndReserve(SESSION, staleSoap).rejectionReason());

        staleSoap.setEvidenciaJabonSecuencia(41L);
        assertTrue(registry.assessAndReserve(SESSION, staleSoap).acceptedEnvelope(),
            "the rejected envelope must not consume the frame sequence");
    }

    @Test
    void soapEvidenceRequiresItsOwnFrameSequenceAndRejectsOrphanSequence() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        DeteccionEvento missing = detection(registration.producerEpoch(), 3L, null);
        missing.setEvidenciaJabon(Map.of("PALMA_IZQUIERDA",
            new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f)));
        assertEquals(ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_REQUIRED,
            registry.assessAndReserve(SESSION, missing).rejectionReason());

        DeteccionEvento orphan = detection(registration.producerEpoch(), 4L, null);
        orphan.setEvidenciaJabonSecuencia(4L);
        assertEquals(ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_WITHOUT_EVIDENCE,
            registry.assessAndReserve(SESSION, orphan).rejectionReason());
    }

    @Test
    void rePairingInvalidatesOldEpochAndRequiresFreshRegistration() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, false);
        var first = registry.registerEpoch(SESSION);

        registry.requireEpoch(SESSION);
        assertEquals(ProducerRejectionReason.EPOCH_NOT_REGISTERED,
            registry.assessAndReserve(SESSION,
                detection(first.producerEpoch(), 1L, null)).rejectionReason());

        var replacement = registry.registerEpoch(SESSION);
        assertTrue(replacement.rotated());
        assertFalse(first.producerEpoch().equals(replacement.producerEpoch()));
        assertEquals(ProducerRejectionReason.EPOCH_MISMATCH,
            registry.assessAndReserve(SESSION,
                detection(first.producerEpoch(), 2L, null)).rejectionReason());
        assertTrue(registry.assessAndReserve(SESSION,
            detection(replacement.producerEpoch(), 0L, null)).acceptedEnvelope());
    }

    @Test
    void controlWatermarkInvalidatesOldFramesAndControlsHaveTheirOwnSequence() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        var registration = registry.registerEpoch(SESSION);

        DeteccionEvento control = new DeteccionEvento(SESSION, "FONDO", 1f, "now");
        control.setProducerEpoch(registration.producerEpoch());
        control.setEventType("CONTROL");
        control.setControlSequence(1L);
        control.setFrameWatermark(12L);
        control.setEvidenciaJabon(Map.of("PALMA_IZQUIERDA",
            new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f)));
        control.setEvidenciaJabonSecuencia(12L);
        assertEquals(ProducerRejectionReason.CONTROL_CARRIES_FRAME_EVIDENCE,
            registry.assessAndReserve(SESSION, control).rejectionReason());
        control.setEvidenciaJabon(Map.of());
        control.setEvidenciaJabonSecuencia(null);
        assertTrue(registry.assessAndReserve(SESSION, control).acceptedEnvelope());

        var staleFrame = registry.assessAndReserve(SESSION,
            detection(registration.producerEpoch(), 12L, null));
        assertEquals(ProducerRejectionReason.FRAME_SEQUENCE_DUPLICATE,
            staleFrame.rejectionReason());

        DeteccionEvento staleControl = new DeteccionEvento(SESSION, "OMS_SIN_EVIDENCIA", 1f, "now");
        staleControl.setProducerEpoch(registration.producerEpoch());
        staleControl.setEventType("CONTROL");
        staleControl.setControlSequence(2L);
        staleControl.setFrameWatermark(11L);
        assertEquals(ProducerRejectionReason.FRAME_WATERMARK_OUT_OF_ORDER,
            registry.assessAndReserve(SESSION, staleControl).rejectionReason());

        DeteccionEvento duplicateControl = new DeteccionEvento(SESSION, "FONDO", 1f, "now");
        duplicateControl.setProducerEpoch(registration.producerEpoch());
        duplicateControl.setEventType("CONTROL");
        duplicateControl.setControlSequence(1L);
        duplicateControl.setFrameWatermark(12L);
        assertEquals(ProducerRejectionReason.CONTROL_SEQUENCE_DUPLICATE,
            registry.assessAndReserve(SESSION, duplicateControl).rejectionReason());
    }

    @Test
    void presenceEnvelopeIsSequencedSeparatelyAndCannotCarryStepEvidence() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        String epoch = registry.registerEpoch(SESSION).producerEpoch();

        assertTrue(registry.assessAndReserve(SESSION, presence(epoch, 1L, 10L, 2))
            .acceptedEnvelope());
        assertTrue(registry.assessAndReserve(SESSION,
            detection(epoch, 10L, null)).acceptedEnvelope(),
            "presence shares its camera watermark but does not consume a detector frame");
        assertEquals(ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_DUPLICATE,
            registry.assessAndReserve(SESSION, presence(epoch, 2L, 10L, 2)).rejectionReason());
        assertTrue(registry.assessAndReserve(SESSION, presence(epoch, 2L, 11L, 2))
            .acceptedEnvelope(), "a rejected presence frame does not consume its control sequence");

        DeteccionEvento presenceWithMovement = presence(epoch, 3L, 12L, 2);
        presenceWithMovement.setEvidenciaMovimiento(
            new com.handwash.model.EvidenciaMovimiento(12L, 2, 0.1, true, 1L));
        assertEquals(ProducerRejectionReason.PRESENCE_CARRIES_DOMAIN_EVIDENCE,
            registry.assessAndReserve(SESSION, presenceWithMovement).rejectionReason());

        DeteccionEvento invalidHands = presence(epoch, 3L, 12L, 3);
        assertEquals(ProducerRejectionReason.PRESENCE_HAND_COUNT_INVALID,
            registry.assessAndReserve(SESSION, invalidHands).rejectionReason());
        assertTrue(registry.assessAndReserve(SESSION, presence(epoch, 3L, 12L, 1))
            .acceptedEnvelope(), "presence hand loss is a valid reset observation");
    }

    @Test
    void presenceCannotRewindBehindAnAcceptedDetectionFrame() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        String epoch = registry.registerEpoch(SESSION).producerEpoch();

        assertTrue(registry.assessAndReserve(SESSION, detection(epoch, 20L, null))
            .acceptedEnvelope());
        assertEquals(ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_OUT_OF_ORDER,
            registry.assessAndReserve(SESSION, presence(epoch, 1L, 19L, 2)).rejectionReason());
    }

    @Test
    void detectionCannotArriveBehindAnAlreadyAcceptedPresenceWatermark() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        String epoch = registry.registerEpoch(SESSION).producerEpoch();

        assertTrue(registry.assessAndReserve(SESSION, presence(epoch, 1L, 20L, 2))
            .acceptedEnvelope());
        assertEquals(ProducerRejectionReason.FRAME_SEQUENCE_OUT_OF_ORDER,
            registry.assessAndReserve(SESSION, detection(epoch, 19L, null)).rejectionReason());
        assertTrue(registry.assessAndReserve(SESSION, detection(epoch, 20L, null))
            .acceptedEnvelope(), "a step result from the same pose frame remains valid");
    }

    @Test
    void controlWatermarkCannotRewindBehindPresenceEvidence() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        String epoch = registry.registerEpoch(SESSION).producerEpoch();
        assertTrue(registry.assessAndReserve(SESSION, presence(epoch, 1L, 20L, 2))
            .acceptedEnvelope());

        assertEquals(ProducerRejectionReason.FRAME_WATERMARK_OUT_OF_ORDER,
            registry.assessAndReserve(SESSION, control(epoch, 2L, 19L)).rejectionReason());
    }

    @Test
    void presenceDwellDoesNotTrustProducerClockAgeAsAcceptanceTime() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, true);
        String epoch = registry.registerEpoch(SESSION).producerEpoch();
        DeteccionEvento delayed = presence(epoch, 0L, 1L, 2);
        delayed.setCaptureAgeMs(60_000L);

        assertTrue(registry.assessAndReserve(SESSION, delayed).acceptedEnvelope(),
            "the Java warmup counts only server monotonic receive time; producer age is diagnostic");
    }

    @Test
    void legacySessionsRejectVersionedEnvelopesAndRemovalDropsState() {
        ProducerProtocolRegistry registry = new ProducerProtocolRegistry();
        registry.initializeSession(SESSION, false);

        DeteccionEvento legacy = new DeteccionEvento(SESSION, "Paso1_Palmas", .9f, "now");
        assertNull(registry.assessAndReserve(SESSION, legacy).rejectionReason());
        assertEquals(ProducerRejectionReason.VERSIONED_FIELDS_ON_LEGACY_SESSION,
            registry.assessAndReserve(SESSION, detection("epoch", 1L, null)).rejectionReason());

        registry.removeSession(SESSION);
        assertEquals(ProducerRejectionReason.VERSIONED_FIELDS_ON_LEGACY_SESSION,
            registry.assessAndReserve(SESSION, detection("epoch", 1L, null)).rejectionReason());
    }

    private DeteccionEvento detection(String epoch, long sequence, Long ageMs) {
        DeteccionEvento event = new DeteccionEvento(SESSION, "PASO_1_PALMAS", .95f, "now");
        event.setProducerEpoch(epoch);
        event.setEventType("DETECTION");
        event.setFrameSequence(sequence);
        event.setCaptureAgeMs(ageMs);
        return event;
    }

    private DeteccionEvento control(String epoch, long sequence, long watermark) {
        DeteccionEvento event = new DeteccionEvento(SESSION, "FONDO", 1f, "now");
        event.setProducerEpoch(epoch);
        event.setEventType("CONTROL");
        event.setControlSequence(sequence);
        event.setFrameWatermark(watermark);
        return event;
    }

    private DeteccionEvento presence(String epoch, long controlSequence,
                                    long frameWatermark, Integer handsVisible) {
        DeteccionEvento event = new DeteccionEvento(SESSION, "PRESENCIA_MANOS", 1f, "now");
        event.setProducerEpoch(epoch);
        event.setEventType("PRESENCE");
        event.setControlSequence(controlSequence);
        event.setFrameWatermark(frameWatermark);
        event.setCaptureAgeMs(10L);
        event.setPresenceHandsVisible(handsVisible);
        return event;
    }
}
