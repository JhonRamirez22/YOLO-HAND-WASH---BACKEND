package com.handwash.intention;

import com.handwash.model.DetectionEvent;
import com.handwash.model.WashStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cadena inmutable compartida; todo el seguimiento mutable pertenece a la sesión. */
@Component
public class WashIntentionChain {
    public static final double DEFAULT_MOVEMENT_THRESHOLD = 1e-8;
    private final IntentionFilter primero;
    private final long confirmacionMs;
    private final long maxIntervaloMs;
    private final int observacionesMinimas;
    private final long pausaMaximaMs;

    @Autowired
    public WashIntentionChain(
        @Value("${handwash.intention.confirmation-ms:650}") long confirmacionMs,
        @Value("${handwash.intention.max-gap-ms:650}") long maxIntervaloMs,
        @Value("${handwash.intention.min-observations:3}") int observacionesMinimas,
        @Value("${handwash.intention.pause-reset-ms:1500}") long pausaMaximaMs,
        @Value("${handwash.intention.start-confidence:0.75}") double confianzaInicio,
        @Value("${handwash.intention.start-minimum-normalized-movement:1.0E-8}")
            double umbralInicioMovimiento,
        @Value("${handwash.intention.step-minimum-normalized-movement:1.0E-8}")
            double umbralPasoMovimiento
    ) {
        if (confirmacionMs < 500 || confirmacionMs > 10_000 || maxIntervaloMs < 100
            || maxIntervaloMs > 1500 || observacionesMinimas < 2 || observacionesMinimas > 100
            || pausaMaximaMs < maxIntervaloMs || pausaMaximaMs > 10_000
            || !Double.isFinite(confianzaInicio) || confianzaInicio < 0.6 || confianzaInicio > 1
            || !umbralMovimientoValido(umbralInicioMovimiento)
            || !umbralMovimientoValido(umbralPasoMovimiento))
            throw new IllegalArgumentException("Configuración de intención de lavado inválida");
        this.confirmacionMs = confirmacionMs;
        this.maxIntervaloMs = maxIntervaloMs;
        this.observacionesMinimas = observacionesMinimas;
        this.pausaMaximaMs = pausaMaximaMs;
        primero = new EvidenciaReciente(new DosManos(new MedicionEspacialValida(
            new GestoCompatible(confianzaInicio), umbralInicioMovimiento, umbralPasoMovimiento)));
    }

    public WashIntentionChain(long confirmacionMs, long maxIntervaloMs,
                                int observacionesMinimas, long pausaMaximaMs,
                                double confianzaInicio) {
        this(confirmacionMs, maxIntervaloMs, observacionesMinimas, pausaMaximaMs,
            confianzaInicio, DEFAULT_MOVEMENT_THRESHOLD, DEFAULT_MOVEMENT_THRESHOLD);
    }

    public String evaluar(DetectionEvent evento, boolean enProgreso) {
        return primero.evaluar(evento, enProgreso);
    }
    public long confirmacionMs() { return confirmacionMs; }
    public long maxIntervaloMs() { return maxIntervaloMs; }
    public int observacionesMinimas() { return observacionesMinimas; }
    public long pausaMaximaMs() { return pausaMaximaMs; }

    private static boolean umbralMovimientoValido(double threshold) {
        return Double.isFinite(threshold) && threshold > 0.0 && threshold <= 1.0;
    }

    private static final class EvidenciaReciente extends IntentionFilter {
        EvidenciaReciente(IntentionFilter siguiente) { super(siguiente); }
        protected String rechazar(DetectionEvent evento, boolean activo) {
            var evidencia = evento.getEvidenciaMovimiento();
            return evidencia == null || evento.getErrorEvidenciaMovimiento() != null
                || !evento.evidenciaMovimientoReciente()
                ? "SIN_EVIDENCIA_RECIENTE" : null;
        }
    }
    private static final class DosManos extends IntentionFilter {
        DosManos(IntentionFilter siguiente) { super(siguiente); }
        protected String rechazar(DetectionEvent evento, boolean activo) {
            return evento.getEvidenciaMovimiento().manosVisibles() < 2 ? "ENCUADRE_INCOMPLETO" : null;
        }
    }
    private static final class MedicionEspacialValida extends IntentionFilter {
        private final double startThreshold;
        private final double stepThreshold;

        MedicionEspacialValida(IntentionFilter siguiente, double startThreshold, double stepThreshold) {
            super(siguiente);
            this.startThreshold = startThreshold;
            this.stepThreshold = stepThreshold;
        }
        protected String rechazar(DetectionEvent evento, boolean activo) {
            var evidencia = evento.getEvidenciaMovimiento();
            // The signed release binds these runtime thresholds to reviewed calibration evidence.
            if (!Boolean.TRUE.equals(evidencia.medicionValida()))
                return "EVIDENCIA_ESPACIAL_NO_VERIFICABLE";
            Double movement = evidencia.movimientoNormalizado();
            if (movement == null || !Double.isFinite(movement))
                return "EVIDENCIA_ESPACIAL_NO_VERIFICABLE";
            double threshold = activo ? stepThreshold : startThreshold;
            if (movement <= threshold)
                return activo ? "MOVIMIENTO_ACTIVO_NO_DETECTADO" : "MOVIMIENTO_INICIAL_NO_DETECTADO";
            return null;
        }
    }
    private static final class GestoCompatible extends IntentionFilter {
        private final double confianzaInicio;
        GestoCompatible(double confianzaInicio) { super(null); this.confianzaInicio = confianzaInicio; }
        protected String rechazar(DetectionEvent evento, boolean activo) {
            WashStep paso = evento.getPasoLavadoResuelto();
            if (paso == null || paso == WashStep.FONDO) return "SIN_GESTO_DE_LAVADO";
            if (!activo && paso != WashStep.PASO_1_PALMAS) return "INICIE_CON_PALMAS";
            // DetectionReceiver already applies the configurable confidence threshold
            // to every published event. Only the initial palms gesture adds a
            // stricter threshold; active phases must not get a second, fixed
            // 0.6 cutoff here.
            return !activo && evento.getConfianza() < confianzaInicio
                ? "GESTO_INCIERTO" : null;
        }
    }
}
