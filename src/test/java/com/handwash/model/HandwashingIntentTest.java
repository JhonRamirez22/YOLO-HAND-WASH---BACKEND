package com.handwash.model;

import com.handwash.intention.HandwashingIntentChain;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class HandwashingIntentTest {
    private final HandwashingIntentChain cadena = new HandwashingIntentChain(1200, 650, 4, 1500, .75);
    private final HandwashingSession sesion = new HandwashingSession("intencion", ProtocolType.DOMESTICO);

    private boolean enviar(HandwashingStep paso, long tiempo, long secuencia, double movimiento, long edad) {
        return enviar(paso, tiempo, secuencia, movimiento, edad, 2);
    }

    private boolean enviar(HandwashingStep paso, long tiempo, long secuencia, double movimiento,
                           long edad, int manosVisibles) {
        var evento = new DetectionEvent("intencion", paso.getClaseModelo(), .95f, Instant.now().toString());
        evento.setServerReceivedAtMonotonicMs(tiempo);
        evento.setEvidenciaMovimiento(
            new MovementEvidence(
                secuencia, manosVisibles, movimiento, manosVisibles == 2, edad));
        boolean permitido = sesion.evaluarIntencion(evento, cadena);
        if (permitido) sesion.procesarDeteccion(paso, tiempo, .95f);
        return permitido;
    }

    private void iniciar() {
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000, 1, .2, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1400, 2, .2, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1800, 3, .2, 100));
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2200, 4, .2, 100));
    }

    private boolean enviarSinMedicion(HandwashingStep paso, long tiempo, long secuencia) {
        var evento = new DetectionEvent("intencion", paso.getClaseModelo(), .95f, Instant.now().toString());
        evento.setServerReceivedAtMonotonicMs(tiempo);
        evento.setEvidenciaMovimiento(new MovementEvidence(secuencia, 2, 0.0, false, 100L));
        boolean permitido = sesion.evaluarIntencion(evento, cadena);
        if (permitido) sesion.procesarDeteccion(paso, tiempo, .95f);
        return permitido;
    }

    private boolean enviarSinEvidencia(HandwashingStep paso, long tiempo, long secuencia) {
        var evento = new DetectionEvent("intencion", paso.getClaseModelo(), .95f, Instant.now().toString());
        evento.setServerReceivedAtMonotonicMs(tiempo);
        evento.setFrameSequence(secuencia);
        // Parcial mode permits transport without pose metadata, but it must never
        // turn that transport ACK into credited evidence for a step.
        evento.setEvidenciaMovimiento(null);
        boolean permitido = sesion.evaluarIntencion(evento, cadena);
        if (permitido) sesion.procesarDeteccion(paso, tiempo, .95f);
        return permitido;
    }

    @Test void manosQuietasNoConfirmanLaIntencionDeIniciarLavado() {
        for (int i = 1; i <= 8; i++) {
            assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000 + i * 400, i, 0, 100));
        }
        assertEquals("MOVIMIENTO_INICIAL_NO_DETECTADO",
            sesion.getEstadoActualResponse().getMotivoIntencion());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, sesion.getEstadoSesion());
        assertEquals(0, sesion.getNumeroIntentoActual());
        assertEquals(0, sesion.getTiempoTotalActivoMs());
        assertTrue(sesion.getHistorialInfracciones().isEmpty());
    }

    @Test void inicioSoloRechazaMovimientoNumericoNuloSinUmbralClinicoDeAmplitud() {
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000, 1, 1e-8, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1400, 2, 1e-6, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1800, 3, 1e-6, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 2200, 4, 1e-6, 100));
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2600, 5, 1e-6, 100));
        assertEquals(HandwashingSessionState.EN_PROGRESO, sesion.getEstadoSesion());
    }

    @Test void fasesActivasAceptanMovimientoBajoPeroMedible() {
        iniciar();
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2600, 5, 1e-6, 100));
        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 2900, 6, 1e-6, 100));
        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 3200, 7, 1e-6, 100));
        assertEquals(HandwashingStep.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
        assertTrue(sesion.getHistorialInfracciones().isEmpty());
    }

    @Test void faseActivaNoAcreditaPasoConMedicionInvalidaNiMovimientoNulo() {
        iniciar();
        long tiempoAntes = sesion.getTiempoTotalActivoMs();

        assertFalse(enviarSinMedicion(HandwashingStep.PASO_2_DORSOS, 2600, 5));
        assertEquals("EVIDENCIA_ESPACIAL_NO_VERIFICABLE",
            sesion.getUltimoCodigoRechazoIntencion());
        assertFalse(enviar(HandwashingStep.PASO_2_DORSOS, 2900, 6, 0.0, 100));
        assertEquals("MOVIMIENTO_ACTIVO_NO_DETECTADO",
            sesion.getUltimoCodigoRechazoIntencion());
        assertEquals(tiempoAntes, sesion.getTiempoTotalActivoMs());
        assertEquals(HandwashingStep.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual());

        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 3200, 7, 1e-6, 100));
        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 3500, 8, 1e-6, 100));
        assertEquals(HandwashingStep.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
    }

    @Test void faseActivaNoAcreditaNiAvanzaConEvidenciaAusenteUnilateralOCaducada() {
        iniciar();
        long tiempoAntes = sesion.getTiempoTotalActivoMs();

        assertFalse(enviarSinEvidencia(HandwashingStep.PASO_2_DORSOS, 2400, 5));
        assertEquals("SIN_EVIDENCIA_RECIENTE", sesion.getEstadoActualResponse().getMotivoIntencion());
        assertEquals("SIN_EVIDENCIA_RECIENTE", sesion.getUltimoCodigoRechazoIntencion());
        assertEquals(tiempoAntes, sesion.getTiempoTotalActivoMs());
        assertEquals(HandwashingStep.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual());

        assertFalse(enviar(HandwashingStep.PASO_2_DORSOS, 2500, 6, .2, 100, 1));
        assertEquals("ENCUADRE_INCOMPLETO", sesion.getEstadoActualResponse().getMotivoIntencion());
        assertEquals("ENCUADRE_INCOMPLETO", sesion.getUltimoCodigoRechazoIntencion());
        assertEquals(tiempoAntes, sesion.getTiempoTotalActivoMs());
        assertEquals(HandwashingStep.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual());

        assertFalse(enviar(HandwashingStep.PASO_2_DORSOS, 2600, 7, .2, 501));
        assertEquals("SIN_EVIDENCIA_RECIENTE", sesion.getEstadoActualResponse().getMotivoIntencion());
        assertEquals(0, sesion.getEstadoActualResponse().getManosVisibles());
        assertEquals("SIN_EVIDENCIA_RECIENTE", sesion.getUltimoCodigoRechazoIntencion());
        assertEquals(tiempoAntes, sesion.getTiempoTotalActivoMs());
        assertEquals(HandwashingStep.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual());

        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 2900, 8, .2, 100));
        assertNull(sesion.getUltimoCodigoRechazoIntencion(),
            "una observación aceptada no debe heredar el motivo del frame inválido anterior");
        assertEquals(HandwashingStep.PASO_1_PALMAS, sesion.getEstadoActual().getPasoActual(),
            "una sola observación válida no debe completar una transición");
        assertTrue(enviar(HandwashingStep.PASO_2_DORSOS, 3200, 9, .2, 100));
        assertEquals(HandwashingStep.PASO_2_DORSOS, sesion.getEstadoActual().getPasoActual());
    }
    @Test void unaSolaManoNoConfirmaIntencionAunqueHayaMovimiento() {
        for (int i = 1; i <= 8; i++) {
            assertFalse(enviar(HandwashingStep.PASO_1_PALMAS,
                1000 + i * 400, i, .2, 100, 1));
        }
        assertEquals("ENCUADRE_INCOMPLETO", sesion.getEstadoActualResponse().getMotivoIntencion());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, sesion.getEstadoSesion());
        assertEquals(0, sesion.getNumeroIntentoActual());
        assertEquals(0, sesion.getTiempoTotalActivoMs());
        assertTrue(sesion.getHistorialInfracciones().isEmpty());
    }
    @Test void deteccionRechazadaPublicaMotivoCandidatoYUmbralParaElDashboard() {
        var evento = new DetectionEvent("intencion", "Paso1_Palmas", .70f, Instant.now().toString());
        evento.setServerReceivedAtMonotonicMs(1000);
        evento.setEvidenciaMovimiento(new MovementEvidence(1L, 2, .2, true, 100L));

        assertFalse(sesion.evaluarIntencion(evento, cadena));
        var estado = sesion.getEstadoActualResponse();
        assertEquals("La confianza no alcanzó el umbral; se reinician los votos de inicio",
            estado.getMotivoIntencion());
        assertEquals("PASO_1_PALMAS", estado.getClaseCandidata());
        assertEquals(.70f, estado.getConfianzaCandidata(), 0.0001f);
        assertEquals(1200L, estado.getUmbralConfirmacionIntencionMs());
        assertEquals(2, estado.getManosVisibles());
    }
    @Test void confirmacionAcreditaSoloLaVentanaObservadaDePalmas() {
        iniciar();
        assertEquals(1200, sesion.getTiempoTotalActivoMs());
        enviar(HandwashingStep.PASO_1_PALMAS, 2600, 5, .2, 100);
        assertEquals(1600, sesion.getTiempoTotalActivoMs());
    }
    @Test void intencionConfirmaConCadenciaVariableDentroDelMaximoPermitido() {
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000, 1, .2, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1400, 2, .2, 100));
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1750, 3, .2, 100));
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2250, 4, .2, 100));
        assertEquals(HandwashingSessionState.EN_PROGRESO, sesion.getEstadoSesion());
    }
    @Test void evidenciaAusenteOCaducadaNoInicia() {
        var evento = new DetectionEvent("intencion", "Paso1_Palmas", .95f, Instant.now().toString());
        evento.setServerReceivedAtMonotonicMs(1000);
        assertFalse(sesion.evaluarIntencion(evento, cadena));
        for (int i = 1; i <= 10; i++) assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000 + i * 400, i, .2, 501));
        assertEquals(0, sesion.getNumeroIntentoActual());
    }
    @Test void otroGestoAntesDeIniciarNoAcusaUnPasoOmitido() {
        for (int i = 1; i <= 10; i++) assertFalse(enviar(HandwashingStep.PASO_3_INTERDIGITALES, 1000 + i * 400, i, .2, 100));
        assertTrue(sesion.getHistorialInfracciones().isEmpty());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, sesion.getEstadoSesion());
    }
    @Test void saltoDePasoReiniciaYExigeNuevaConfirmacion() {
        iniciar();
        enviar(HandwashingStep.PASO_3_INTERDIGITALES, 2600, 5, .2, 100);
        assertEquals(0, sesion.getIntentosReiniciados());
        enviar(HandwashingStep.PASO_3_INTERDIGITALES, 2900, 6, .2, 100);
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals(ViolationType.PASO_OMITIDO, sesion.getEstadoActualResponse().getUltimoErrorReinicio().getTipo());
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 3200, 7, .2, 100));
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, sesion.getEstadoSesion());
        assertEquals(0, sesion.getTiempoTotalActivoMs());
    }
    @Test void movimientoBajoPeroMedibleNoSeDescartaEnFaseActiva() {
        iniciar();
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2400, 5, 1e-6, 100));
        assertEquals("LAVADO_PROBABLE", sesion.getEstadoActualResponse().getEstadoIntencion());
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2800, 6, 1e-6, 100));
        assertEquals(1800, sesion.getTiempoTotalActivoMs());
        assertEquals(0, sesion.getIntentosReiniciados());
    }
    @Test void huecoMenorAlMaximoDeContinuidadSeAcreditaSinInventarOtrosIntervalos() {
        iniciar();
        enviar(HandwashingStep.PASO_1_PALMAS, 3100, 5, .2, 100);
        assertEquals(2100, sesion.getTiempoTotalActivoMs());
    }
    @Test void pausaLargaReiniciaAntesDeReanudar() {
        iniciar();
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 4000, 5, .2, 100));
        assertEquals(1, sesion.getIntentosReiniciados());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, sesion.getEstadoSesion());
    }
    @Test void fondoViejoNoReiniciaIntentoConEvidenciaMasNueva() {
        iniciar();
        assertFalse(enviar(HandwashingStep.FONDO, 2400, 2, .2, 100));
        assertEquals(0, sesion.getIntentosReiniciados());
        assertEquals(HandwashingSessionState.EN_PROGRESO, sesion.getEstadoSesion());
    }
    @Test void duplicadoNoSumaNiBorraConfirmacionValida() {
        enviar(HandwashingStep.PASO_1_PALMAS, 1000, 1, .2, 100);
        enviar(HandwashingStep.PASO_1_PALMAS, 1400, 2, .2, 100);
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1600, 2, .2, 100));
        enviar(HandwashingStep.PASO_1_PALMAS, 1800, 3, .2, 100);
        assertTrue(enviar(HandwashingStep.PASO_1_PALMAS, 2200, 4, .2, 100));
    }

    @Test void reinicioNoPublicaManosDelIntentoAnteriorComoEvidenciaReciente() {
        assertFalse(enviar(HandwashingStep.PASO_1_PALMAS, 1000, 1, .2, 100));
        assertEquals(2, sesion.getEstadoActualResponse().getManosVisibles());

        sesion.reiniciarIntento("repetir desde el inicio");

        var estado = sesion.getEstadoActualResponse();
        assertEquals(0, estado.getManosVisibles());
        assertEquals("SIN_EVIDENCIA", estado.getEstadoIntencion());
        assertEquals("Esperando evidencia reciente", estado.getMotivoIntencion());
    }

    @Test void expirationClearsPartialModeCandidateAndLiveVisualEvidence() {
        iniciar();
        var before = sesion.getEstadoActualResponse();
        assertEquals(2, before.getManosVisibles());
        assertEquals("PASO_1_PALMAS", before.getClaseCandidata());
        assertEquals(.95f, before.getConfianzaCandidata(), .0001f);

        sesion.expirar();

        var expired = sesion.getEstadoActualResponse();
        assertEquals(HandwashingSessionState.EXPIRADA, sesion.getEstadoSesion());
        assertEquals(0, expired.getManosVisibles());
        assertEquals("SIN_EVIDENCIA", expired.getEstadoIntencion());
        assertNull(expired.getClaseCandidata());
        assertEquals(0.0f, expired.getConfianzaCandidata(), .0001f);
        assertEquals(0.0f, expired.getConfianzaDeteccion(), .0001f);
    }

    @Test void interleavedSessionsDoNotShareSequenceOrIntentVotes() {
        var first = new HandwashingSession("first", ProtocolType.DOMESTICO);
        var second = new HandwashingSession("second", ProtocolType.DOMESTICO);

        assertFalse(evaluate(first, "first", 1000, 1));
        assertFalse(evaluate(second, "second", 20_000, 1));
        assertFalse(evaluate(first, "first", 1400, 2));
        assertFalse(evaluate(first, "first", 1800, 3));
        assertTrue(evaluate(first, "first", 2200, 4));

        assertEquals(HandwashingSessionState.EN_PROGRESO, first.getEstadoSesion());
        assertEquals(HandwashingSessionState.ESPERANDO_INICIO, second.getEstadoSesion());
        assertEquals(0, second.getNumeroIntentoActual());
    }

    private boolean evaluate(HandwashingSession target, String sessionId, long time, long sequence) {
        var event = new DetectionEvent(sessionId, "Paso1_Palmas", .95f, Instant.now().toString());
        event.setServerReceivedAtMonotonicMs(time);
        event.setEvidenciaMovimiento(new MovementEvidence(sequence, 2, .2, true, 100L));
        boolean accepted = target.evaluarIntencion(event, cadena);
        if (accepted) target.procesarDeteccion(HandwashingStep.PASO_1_PALMAS, time, .95f);
        return accepted;
    }
}
