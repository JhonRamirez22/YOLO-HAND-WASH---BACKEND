package com.handwash.agent;

import tools.jackson.databind.ObjectMapper;
import com.handwash.model.DetectionEvent;
import com.handwash.model.MovementEvidence;
import com.handwash.model.SoapEvidence;
import com.handwash.model.SoapEvidenceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReceiverValidationTest {
    @Test
    void rejectsMalformedAndOutOfWindowDetectionsBeforePublishing() {
        Receiver receptor = new Receiver();
        AtomicInteger published = new AtomicInteger();
        receptor.addObserver(evento -> published.incrementAndGet());

        assertNull(receptor.recibir(new DetectionEvent("s1", "Paso1_Palmas", 0.9f, "not-a-time")));
        assertNull(receptor.recibir(new DetectionEvent("s1", "Paso1_Palmas", 0.9f, null)));
        assertNull(receptor.recibir(new DetectionEvent("s1", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(60).toString())));
        assertNull(receptor.recibir(new DetectionEvent("s1", "Paso1_Palmas", Float.NaN,
            Instant.now().toString())));
        assertNull(receptor.recibir(new DetectionEvent("s1", "unknown", 0.9f,
            Instant.now().toString())));
        assertEquals(0, published.get());

        assertNotNull(receptor.recibir(new DetectionEvent("s1", "Paso1_Palmas", 0.9f,
            Instant.now().toString())));
        assertEquals(1, published.get());
    }

    @Test
    void futureClientTimestampCannotCreditWashTime() {
        Receiver receptor = new Receiver();
        com.handwash.model.HandwashingSession sesion = new com.handwash.model.HandwashingSession(
            "trusted-clock", com.handwash.model.ProtocolType.DOMESTICO);
        receptor.addObserver(evento -> sesion.procesarDeteccion(
            com.handwash.model.HandwashingStep.fromClaseModelo(evento.getClaseDetectada()),
            evento.getServerReceivedAtMonotonicMs()));

        receptor.recibir(new DetectionEvent("trusted-clock", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(5).toString()));
        receptor.recibir(new DetectionEvent("trusted-clock", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(25).toString()));

        assertTrue(sesion.getEstadoActualResponse().getTiempoAcumuladoMs() < 1_000L);
    }

    @Test
    void confidenceFilteredObservationStillGetsTrustedServerTime() {
        Receiver receptor = new Receiver();
        String clientTimestamp = Instant.now().plusSeconds(10).toString();
        DetectionEvent filtered = new DetectionEvent(
            "filtered-clock", "Paso1_Palmas", 0.2f, clientTimestamp);

        assertNull(receptor.recibir(filtered));
        assertTrue(filtered.getServerReceivedAtMonotonicMs() > 0L);
        assertNotEquals(clientTimestamp, filtered.getTimestamp());
    }

    @Test
    void acceptedEventsReceiveAnInternalMonotonicTimestampFromTheServer() throws Exception {
        Receiver receptor = new Receiver();
        AtomicReference<DetectionEvent> published = new AtomicReference<>();
        receptor.addObserver(published::set);
        String timestamp = Instant.now().toString();
        DetectionEvent input = new ObjectMapper().readValue("""
            {"sessionId":"monotonic-clock","claseDetectada":"Paso1_Palmas",
             "confianza":0.9,"timestamp":"%s","serverReceivedAtMonotonicMs":9223372036854775807,
             "serverIngressAtMonotonicNanos":0,
             "evidenciaMovimiento":{"secuencia":1,"manosVisibles":2,
               "movimientoNormalizado":0.2,"medicionValida":true,"antiguedadMs":500}}
            """.formatted(timestamp), DetectionEvent.class);
        assertEquals(0L, input.getServerReceivedAtMonotonicMs(),
            "El cliente no puede proporcionar el reloj monotónico interno");
        assertTrue(input.evidenciaMovimientoReciente(),
            "El cliente tampoco puede inyectar la marca monotónica de ingreso HTTP");
        input.setServerReceivedAtMonotonicMs(Long.MAX_VALUE);

        DetectionEvent accepted = receptor.recibir(input);

        assertNotNull(accepted);
        assertSame(accepted, published.get());
        assertTrue(accepted.getServerReceivedAtMonotonicMs() > 0L);
        assertNotEquals(Long.MAX_VALUE, accepted.getServerReceivedAtMonotonicMs());
        assertFalse(new ObjectMapper().writeValueAsString(accepted)
            .contains("serverReceivedAtMonotonicMs"));
    }

    @Test
    void washTimingUsesHttpIngressTimeInsteadOfLockProcessingDelay() throws Exception {
        Receiver receptor = new Receiver();
        com.handwash.model.HandwashingSession session = new com.handwash.model.HandwashingSession(
            "ingress-timing", com.handwash.model.ProtocolType.DOMESTICO);
        receptor.addObserver(event -> session.procesarDeteccion(
            event.getPasoLavadoResuelto(), event.getServerReceivedAtMonotonicMs(), event.getConfianza()));
        long firstIngress = System.nanoTime();
        DetectionEvent first = new DetectionEvent(
            "ingress-timing", "Paso1_Palmas", 0.9f, Instant.now().toString());
        first.setServerIngressAtMonotonicNanos(firstIngress);
        DetectionEvent acceptedFirst = receptor.recibir(first);
        assertNotNull(acceptedFirst);

        Thread.sleep(150L);
        DetectionEvent second = new DetectionEvent(
            "ingress-timing", "Paso1_Palmas", 0.9f, Instant.now().toString());
        second.setServerIngressAtMonotonicNanos(
            firstIngress + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20L));
        DetectionEvent acceptedSecond = receptor.recibir(second);

        assertNotNull(acceptedSecond);
        long creditedIntervalMs = acceptedSecond.getServerReceivedAtMonotonicMs()
            - acceptedFirst.getServerReceivedAtMonotonicMs();
        assertTrue(creditedIntervalMs >= 0L && creditedIntervalMs < 80L,
            "wash timing must exclude the 150 ms spent waiting before Java processing");
        assertTrue(session.getTiempoTotalActivoMs() < 80L,
            "SessionManager's step accumulator must not credit the processing queue delay");
    }

    @Test
    void acceptsSoapEvidenceDuringRubbingButRejectsItAfterRinsingStarts() {
        Receiver receptor = new Receiver();
        Map<String, SoapEvidence> evidence = Map.of(
            "PULGAR_IZQUIERDO", new SoapEvidence(SoapEvidenceStatus.ESPUMA_VISIBLE, 0.95f));
        DetectionEvent rubbing = new DetectionEvent(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        rubbing.setEvidenciaJabon(evidence);
        rubbing.setEvidenciaMovimiento(new MovementEvidence(1L, 2, 0.2, true, 100L));
        rubbing.setEvidenciaJabonSecuencia(1L);
        assertNull(receptor.validarEstructura(rubbing));

        DetectionEvent mismatched = new DetectionEvent(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        mismatched.setEvidenciaJabon(evidence);
        mismatched.setEvidenciaMovimiento(new MovementEvidence(2L, 2, 0.2, true, 100L));
        mismatched.setEvidenciaJabonSecuencia(1L);
        assertTrue(receptor.validarEstructura(mismatched).contains("no coincide"));

        DetectionEvent missingSequence = new DetectionEvent(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        missingSequence.setEvidenciaJabon(evidence);
        missingSequence.setEvidenciaMovimiento(new MovementEvidence(3L, 2, 0.2, true, 100L));
        assertTrue(receptor.validarEstructura(missingSequence).contains("es obligatoria"));

        DetectionEvent rinsing = new DetectionEvent(
            "soap-phase", "OMS_09_ENJUAGAR_MANOS", 0.9f, Instant.now().toString());
        rinsing.setEvidenciaJabon(evidence);
        rinsing.setEvidenciaMovimiento(new MovementEvidence(2L, 2, 0.2, true, 100L));
        rinsing.setEvidenciaJabonSecuencia(2L);
        assertNotNull(receptor.validarEstructura(rinsing));
    }

    @Test
    void omsActionsRequireExactlyTwoHandsValidMeasurementAndFreshEvidence() {
        Receiver receptor = new Receiver();
        String timestamp = Instant.now().toString();

        DetectionEvent missing = new DetectionEvent(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        assertTrue(receptor.validarEstructura(missing).contains("pose bilateral"));

        DetectionEvent oneHand = new DetectionEvent(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        oneHand.setEvidenciaMovimiento(new MovementEvidence(1L, 1, 0.2, false, 100L));
        assertTrue(receptor.validarEstructura(oneHand).contains("exactamente dos manos"));

        DetectionEvent unmeasured = new DetectionEvent(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        unmeasured.setEvidenciaMovimiento(new MovementEvidence(2L, 2, 0.0, false, 100L));
        assertTrue(receptor.validarEstructura(unmeasured).contains("medición espacial válida"));

        DetectionEvent stale = new DetectionEvent(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        stale.setEvidenciaMovimiento(new MovementEvidence(3L, 2, 0.2, true, 501L));
        assertTrue(receptor.validarEstructura(stale).contains("caducada"));

        DetectionEvent valid = new DetectionEvent(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        valid.setEvidenciaMovimiento(new MovementEvidence(4L, 2, 0.0, true, 500L));
        assertNull(receptor.validarEstructura(valid));
    }

    @Test
    void frameSequenceMustMatchSpatialEvidenceEvenForUnversionedRequests() {
        Receiver receptor = new Receiver();
        DetectionEvent event = new DetectionEvent(
            "mixed-frames", "OMS_03_FROTAR_PALMAS", 0.9f, Instant.now().toString());
        event.setFrameSequence(12L);
        event.setEvidenciaMovimiento(new MovementEvidence(11L, 2, 0.2, true, 100L));
        assertTrue(receptor.validarEstructura(event).contains("no coincide con frameSequence"));
    }

    @Test
    void serverQueueDelayConsumesTheRemainingMovementEvidenceFreshness() {
        Receiver receptor = new Receiver();
        DetectionEvent delayed = new DetectionEvent(
            "oms-queued-evidence", "OMS_01_MOJAR_MANOS", 0.9f, Instant.now().toString());
        delayed.setEvidenciaMovimiento(new MovementEvidence(10L, 2, 0.2, true, 350L));
        delayed.setServerIngressAtMonotonicNanos(
            System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L));

        assertTrue(receptor.validarEstructura(delayed).contains("caducada"),
            "350 ms of producer age plus Java queue delay must exceed the 500 ms freshness budget");
    }

    @Test
    void serverQueueDelayDoesNotRejectSpatialEvidenceStillWithinItsFreshnessBudget() {
        Receiver receptor = new Receiver();
        DetectionEvent delayed = new DetectionEvent(
            "oms-current-evidence", "OMS_01_MOJAR_MANOS", 0.9f, Instant.now().toString());
        delayed.setEvidenciaMovimiento(new MovementEvidence(11L, 2, 0.2, true, 150L));
        delayed.setServerIngressAtMonotonicNanos(
            System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L));

        assertNull(receptor.validarEstructura(delayed));
    }

    @Test
    void riskAlertFailsClosedEvenWhenBilateralPoseIsUnavailable() {
        Receiver receptor = new Receiver();
        AtomicReference<DetectionEvent> published = new AtomicReference<>();
        receptor.addObserver(published::set);
        DetectionEvent risk = new DetectionEvent(
            "oms-risk-without-pose", "OMS_CONTACTO_RIESGO", 0.9f, Instant.now().toString());

        assertNull(receptor.validarEstructura(risk));
        assertSame(risk, receptor.recibir(risk));
        assertSame(risk, published.get());
    }

    @Test
    void omsNoEvidenceControlDoesNotRequireFramePose() {
        Receiver receptor = new Receiver();
        DetectionEvent control = new DetectionEvent(
            "oms-control", "OMS_SIN_EVIDENCIA", 1.0f, Instant.now().toString());
        assertNull(receptor.validarEstructura(control));
    }
}
