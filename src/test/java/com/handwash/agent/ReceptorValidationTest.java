package com.handwash.agent;

import tools.jackson.databind.ObjectMapper;
import com.handwash.model.DeteccionEvento;
import com.handwash.model.EvidenciaMovimiento;
import com.handwash.model.EvidenciaJabon;
import com.handwash.model.EstadoEvidenciaJabon;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReceptorValidationTest {
    @Test
    void rejectsMalformedAndOutOfWindowDetectionsBeforePublishing() {
        Receptor receptor = new Receptor();
        AtomicInteger published = new AtomicInteger();
        receptor.addObserver(evento -> published.incrementAndGet());

        assertNull(receptor.recibir(new DeteccionEvento("s1", "Paso1_Palmas", 0.9f, "not-a-time")));
        assertNull(receptor.recibir(new DeteccionEvento("s1", "Paso1_Palmas", 0.9f, null)));
        assertNull(receptor.recibir(new DeteccionEvento("s1", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(60).toString())));
        assertNull(receptor.recibir(new DeteccionEvento("s1", "Paso1_Palmas", Float.NaN,
            Instant.now().toString())));
        assertNull(receptor.recibir(new DeteccionEvento("s1", "unknown", 0.9f,
            Instant.now().toString())));
        assertEquals(0, published.get());

        assertNotNull(receptor.recibir(new DeteccionEvento("s1", "Paso1_Palmas", 0.9f,
            Instant.now().toString())));
        assertEquals(1, published.get());
    }

    @Test
    void futureClientTimestampCannotCreditWashTime() {
        Receptor receptor = new Receptor();
        com.handwash.model.SesionLavado sesion = new com.handwash.model.SesionLavado(
            "trusted-clock", com.handwash.model.TipoProtocolo.DOMESTICO);
        receptor.addObserver(evento -> sesion.procesarDeteccion(
            com.handwash.model.PasoLavado.fromClaseModelo(evento.getClaseDetectada()),
            evento.getServerReceivedAtMonotonicMs()));

        receptor.recibir(new DeteccionEvento("trusted-clock", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(5).toString()));
        receptor.recibir(new DeteccionEvento("trusted-clock", "Paso1_Palmas", 0.9f,
            Instant.now().plusSeconds(25).toString()));

        assertTrue(sesion.getEstadoActualResponse().getTiempoAcumuladoMs() < 1_000L);
    }

    @Test
    void confidenceFilteredObservationStillGetsTrustedServerTime() {
        Receptor receptor = new Receptor();
        String clientTimestamp = Instant.now().plusSeconds(10).toString();
        DeteccionEvento filtered = new DeteccionEvento(
            "filtered-clock", "Paso1_Palmas", 0.2f, clientTimestamp);

        assertNull(receptor.recibir(filtered));
        assertTrue(filtered.getServerReceivedAtMonotonicMs() > 0L);
        assertNotEquals(clientTimestamp, filtered.getTimestamp());
    }

    @Test
    void acceptedEventsReceiveAnInternalMonotonicTimestampFromTheServer() throws Exception {
        Receptor receptor = new Receptor();
        AtomicReference<DeteccionEvento> published = new AtomicReference<>();
        receptor.addObserver(published::set);
        String timestamp = Instant.now().toString();
        DeteccionEvento input = new ObjectMapper().readValue("""
            {"sessionId":"monotonic-clock","claseDetectada":"Paso1_Palmas",
             "confianza":0.9,"timestamp":"%s","serverReceivedAtMonotonicMs":9223372036854775807,
             "serverIngressAtMonotonicNanos":0,
             "evidenciaMovimiento":{"secuencia":1,"manosVisibles":2,
               "movimientoNormalizado":0.2,"medicionValida":true,"antiguedadMs":500}}
            """.formatted(timestamp), DeteccionEvento.class);
        assertEquals(0L, input.getServerReceivedAtMonotonicMs(),
            "El cliente no puede proporcionar el reloj monotónico interno");
        assertTrue(input.evidenciaMovimientoReciente(),
            "El cliente tampoco puede inyectar la marca monotónica de ingreso HTTP");
        input.setServerReceivedAtMonotonicMs(Long.MAX_VALUE);

        DeteccionEvento accepted = receptor.recibir(input);

        assertNotNull(accepted);
        assertSame(accepted, published.get());
        assertTrue(accepted.getServerReceivedAtMonotonicMs() > 0L);
        assertNotEquals(Long.MAX_VALUE, accepted.getServerReceivedAtMonotonicMs());
        assertFalse(new ObjectMapper().writeValueAsString(accepted)
            .contains("serverReceivedAtMonotonicMs"));
    }

    @Test
    void washTimingUsesHttpIngressTimeInsteadOfLockProcessingDelay() throws Exception {
        Receptor receptor = new Receptor();
        com.handwash.model.SesionLavado session = new com.handwash.model.SesionLavado(
            "ingress-timing", com.handwash.model.TipoProtocolo.DOMESTICO);
        receptor.addObserver(event -> session.procesarDeteccion(
            event.getPasoLavadoResuelto(), event.getServerReceivedAtMonotonicMs(), event.getConfianza()));
        long firstIngress = System.nanoTime();
        DeteccionEvento first = new DeteccionEvento(
            "ingress-timing", "Paso1_Palmas", 0.9f, Instant.now().toString());
        first.setServerIngressAtMonotonicNanos(firstIngress);
        DeteccionEvento acceptedFirst = receptor.recibir(first);
        assertNotNull(acceptedFirst);

        Thread.sleep(150L);
        DeteccionEvento second = new DeteccionEvento(
            "ingress-timing", "Paso1_Palmas", 0.9f, Instant.now().toString());
        second.setServerIngressAtMonotonicNanos(
            firstIngress + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20L));
        DeteccionEvento acceptedSecond = receptor.recibir(second);

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
        Receptor receptor = new Receptor();
        Map<String, EvidenciaJabon> evidence = Map.of(
            "PULGAR_IZQUIERDO", new EvidenciaJabon(EstadoEvidenciaJabon.ESPUMA_VISIBLE, 0.95f));
        DeteccionEvento rubbing = new DeteccionEvento(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        rubbing.setEvidenciaJabon(evidence);
        rubbing.setEvidenciaMovimiento(new EvidenciaMovimiento(1L, 2, 0.2, true, 100L));
        rubbing.setEvidenciaJabonSecuencia(1L);
        assertNull(receptor.validarEstructura(rubbing));

        DeteccionEvento mismatched = new DeteccionEvento(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        mismatched.setEvidenciaJabon(evidence);
        mismatched.setEvidenciaMovimiento(new EvidenciaMovimiento(2L, 2, 0.2, true, 100L));
        mismatched.setEvidenciaJabonSecuencia(1L);
        assertTrue(receptor.validarEstructura(mismatched).contains("no coincide"));

        DeteccionEvento missingSequence = new DeteccionEvento(
            "soap-phase", "OMS_07_FROTAR_PULGARES", 0.9f, Instant.now().toString());
        missingSequence.setEvidenciaJabon(evidence);
        missingSequence.setEvidenciaMovimiento(new EvidenciaMovimiento(3L, 2, 0.2, true, 100L));
        assertTrue(receptor.validarEstructura(missingSequence).contains("es obligatoria"));

        DeteccionEvento rinsing = new DeteccionEvento(
            "soap-phase", "OMS_09_ENJUAGAR_MANOS", 0.9f, Instant.now().toString());
        rinsing.setEvidenciaJabon(evidence);
        rinsing.setEvidenciaMovimiento(new EvidenciaMovimiento(2L, 2, 0.2, true, 100L));
        rinsing.setEvidenciaJabonSecuencia(2L);
        assertNotNull(receptor.validarEstructura(rinsing));
    }

    @Test
    void omsActionsRequireExactlyTwoHandsValidMeasurementAndFreshEvidence() {
        Receptor receptor = new Receptor();
        String timestamp = Instant.now().toString();

        DeteccionEvento missing = new DeteccionEvento(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        assertTrue(receptor.validarEstructura(missing).contains("pose bilateral"));

        DeteccionEvento oneHand = new DeteccionEvento(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        oneHand.setEvidenciaMovimiento(new EvidenciaMovimiento(1L, 1, 0.2, false, 100L));
        assertTrue(receptor.validarEstructura(oneHand).contains("exactamente dos manos"));

        DeteccionEvento unmeasured = new DeteccionEvento(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        unmeasured.setEvidenciaMovimiento(new EvidenciaMovimiento(2L, 2, 0.0, false, 100L));
        assertTrue(receptor.validarEstructura(unmeasured).contains("medición espacial válida"));

        DeteccionEvento stale = new DeteccionEvento(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        stale.setEvidenciaMovimiento(new EvidenciaMovimiento(3L, 2, 0.2, true, 501L));
        assertTrue(receptor.validarEstructura(stale).contains("caducada"));

        DeteccionEvento valid = new DeteccionEvento(
            "oms-evidence", "OMS_01_MOJAR_MANOS", 0.9f, timestamp);
        valid.setEvidenciaMovimiento(new EvidenciaMovimiento(4L, 2, 0.0, true, 500L));
        assertNull(receptor.validarEstructura(valid));
    }

    @Test
    void frameSequenceMustMatchSpatialEvidenceEvenForUnversionedRequests() {
        Receptor receptor = new Receptor();
        DeteccionEvento event = new DeteccionEvento(
            "mixed-frames", "OMS_03_FROTAR_PALMAS", 0.9f, Instant.now().toString());
        event.setFrameSequence(12L);
        event.setEvidenciaMovimiento(new EvidenciaMovimiento(11L, 2, 0.2, true, 100L));
        assertTrue(receptor.validarEstructura(event).contains("no coincide con frameSequence"));
    }

    @Test
    void serverQueueDelayConsumesTheRemainingMovementEvidenceFreshness() {
        Receptor receptor = new Receptor();
        DeteccionEvento delayed = new DeteccionEvento(
            "oms-queued-evidence", "OMS_01_MOJAR_MANOS", 0.9f, Instant.now().toString());
        delayed.setEvidenciaMovimiento(new EvidenciaMovimiento(10L, 2, 0.2, true, 350L));
        delayed.setServerIngressAtMonotonicNanos(
            System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L));

        assertTrue(receptor.validarEstructura(delayed).contains("caducada"),
            "350 ms of producer age plus Java queue delay must exceed the 500 ms freshness budget");
    }

    @Test
    void serverQueueDelayDoesNotRejectSpatialEvidenceStillWithinItsFreshnessBudget() {
        Receptor receptor = new Receptor();
        DeteccionEvento delayed = new DeteccionEvento(
            "oms-current-evidence", "OMS_01_MOJAR_MANOS", 0.9f, Instant.now().toString());
        delayed.setEvidenciaMovimiento(new EvidenciaMovimiento(11L, 2, 0.2, true, 150L));
        delayed.setServerIngressAtMonotonicNanos(
            System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L));

        assertNull(receptor.validarEstructura(delayed));
    }

    @Test
    void riskAlertFailsClosedEvenWhenBilateralPoseIsUnavailable() {
        Receptor receptor = new Receptor();
        AtomicReference<DeteccionEvento> published = new AtomicReference<>();
        receptor.addObserver(published::set);
        DeteccionEvento risk = new DeteccionEvento(
            "oms-risk-without-pose", "OMS_CONTACTO_RIESGO", 0.9f, Instant.now().toString());

        assertNull(receptor.validarEstructura(risk));
        assertSame(risk, receptor.recibir(risk));
        assertSame(risk, published.get());
    }

    @Test
    void omsNoEvidenceControlDoesNotRequireFramePose() {
        Receptor receptor = new Receptor();
        DeteccionEvento control = new DeteccionEvento(
            "oms-control", "OMS_SIN_EVIDENCIA", 1.0f, Instant.now().toString());
        assertNull(receptor.validarEstructura(control));
    }
}
