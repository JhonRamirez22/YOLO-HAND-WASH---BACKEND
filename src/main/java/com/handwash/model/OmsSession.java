package com.handwash.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Strict evaluator for the full WHO flow; visual-model readiness is a separate approval gate. */
public class OmsSession {
    private static final long DEFAULT_MIN_OBSERVATION_PER_PHASE_MS = 600L;
    private static final long MIN_WHO_CYCLE_MS = 40_000L;
    private static final float MIN_SOAP_REGION_CONFIDENCE = 0.75f;
    private static final int REQUIRED_SOAP_REGION_OBSERVATIONS = 2;
    /** A duration needs at least two distinct observations to be measurable. */
    private static final int MIN_PHASE_OBSERVATIONS = 2;
    private static final int MAX_INFRACTIONS = 500;
    private static final int MAX_ATTEMPT_INFRACTIONS = 50;
    private static final int MAX_ATTEMPTS = 100;

    private record ActionCandidate(
        OmsAction action,
        long firstObservedAtMs,
        Map<String, SoapEvidence> firstEvidence
    ) {}

    private final String sessionId;
    private final boolean modelReady;
    private final long minimumTotalMs;
    private final long minimumObservationPerPhaseMs;
    private final long maxDetectionGapMs;
    private final long sessionStartMs = System.currentTimeMillis();
    private final long sessionStartMonotonicNanos = System.nanoTime();
    private final Map<OmsAction, Long> activeMsByAction = new EnumMap<>(OmsAction.class);
    private final Map<SoapRegion, Integer> foamObservations = new EnumMap<>(SoapRegion.class);
    private final Map<SoapRegion, Long> lastFoamObservationMs = new EnumMap<>(SoapRegion.class);
    private final Map<SoapRegion, SoapEvidenceStatus> soapCoverage = new EnumMap<>(SoapRegion.class);
    private final List<Violation> infractions = new ArrayList<>();
    private final List<Violation> currentAttemptInfractions = new ArrayList<>();
    private final List<HandwashingAttemptSummary> attempts = new ArrayList<>();

    private OmsAction currentAction;
    private ActionCandidate actionCandidate;
    private HandwashingSessionState status = HandwashingSessionState.ESPERANDO_INICIO;
    private Violation currentInfraction;
    private Violation lastRetryError;
    private long lastDetectionMs;
    private long lastObservationMs;
    private int currentActionObservations;
    private long lastActivityMs = sessionStartMs;
    private long lastActivityMonotonicNanos;
    private long attemptStartMonotonicNanos;
    private long finishedAtMs;
    private long finishedAtMonotonicNanos;
    private long lastInfractionMonotonicNanos;
    private long currentAttemptLastInfractionMonotonicNanos;
    private int currentAttempt;
    private int retries;
    private int omittedInfractions;
    private int acceptedDetections;
    private double confidenceSum;
    private float latestConfidence;
    private boolean coverageComplete;
    private boolean handEvidencePresent;

    public OmsSession(String sessionId, boolean modelReady, long maxDetectionGapMs) {
        this(sessionId, modelReady, maxDetectionGapMs, MIN_WHO_CYCLE_MS);
    }

    public OmsSession(String sessionId, boolean modelReady, long maxDetectionGapMs, long minimumTotalMs) {
        this(sessionId, modelReady, maxDetectionGapMs, minimumTotalMs,
            DEFAULT_MIN_OBSERVATION_PER_PHASE_MS);
    }

    public OmsSession(String sessionId, boolean modelReady, long maxDetectionGapMs,
                     long minimumTotalMs, long minimumObservationPerPhaseMs) {
        if (maxDetectionGapMs < 100L || maxDetectionGapMs > 5_000L) {
            throw new IllegalArgumentException("maxDetectionGapMs debe estar entre 100 y 5000");
        }
        if (minimumTotalMs < 40_000L || minimumTotalMs > 60_000L) {
            throw new IllegalArgumentException("minimumTotalMs debe estar entre 40000 y 60000");
        }
        if (minimumObservationPerPhaseMs < 100L || minimumObservationPerPhaseMs > 5_000L) {
            throw new IllegalArgumentException("minimumObservationPerPhaseMs debe estar entre 100 y 5000");
        }
        this.sessionId = sessionId;
        this.modelReady = modelReady;
        this.maxDetectionGapMs = maxDetectionGapMs;
        this.minimumTotalMs = minimumTotalMs;
        this.minimumObservationPerPhaseMs = minimumObservationPerPhaseMs;
        this.lastActivityMonotonicNanos = System.nanoTime();
        resetSoapCoverage();
    }

    public synchronized HandwashingStatusResponse procesar(
        OmsAction action,
        Map<String, SoapEvidence> evidence,
        long receivedAtMs,
        float confidence
    ) {
        if (status == HandwashingSessionState.COMPLETADA || status == HandwashingSessionState.EXPIRADA) {
            return response();
        }
        if (action == null) {
            descartarObservacion("Se recibió una acción OMS inválida; reinicie desde mojar las manos");
            return response();
        }
        if (receivedAtMs <= 0L) {
            descartarObservacion("La observación OMS no tiene un tiempo monotónico válido");
            return response();
        }
        if (!Float.isFinite(confidence) || confidence < 0.0f || confidence > 1.0f) {
            descartarObservacion("La observación OMS tiene una confianza inválida");
            return response();
        }
        if (lastObservationMs > 0L && receivedAtMs <= lastObservationMs) return response();

        // The camera stream and HTTP sender can pause independently. A phase
        // observed before the pause cannot be continued as if the missing
        // interval were visible, even when the next label is the same phase.
        // Risk contact is an independent fail-closed alert, not a phase that
        // may be discarded by the ordinary gap-recovery path. Report it even
        // when it is the first observation after an occlusion or camera pause.
        if (!action.esRiesgo() && currentAction != null && lastObservationMs > 0L
            && receivedAtMs - lastObservationMs > maxDetectionGapMs) {
            report(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
                "Se perdió la observación continua durante más de " + maxDetectionGapMs
                    + " ms; repita desde mojar las manos",
                currentAction.name());
            restartAttempt("EVIDENCIA_VISUAL_INTERRUPTA");
            latestConfidence = 0.0f;
            return response();
        }

        if (action.esSinEvidencia()) {
            actionCandidate = null;
            // A hidden interval can include an unseen contamination contact.
            // The complete-procedure evaluator must fail closed, not resume.
            if (currentAction != null) {
                report(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
                    "Se perdió la observación continua de las manos o la acción; repita desde mojar las manos",
                    currentAction.name());
                restartAttempt("EVIDENCIA_VISUAL_INTERRUPTA");
            }
            latestConfidence = 0.0f;
            handEvidencePresent = false;
            return response();
        }

        lastObservationMs = receivedAtMs;
        currentInfraction = null;
        markActivity();
        handEvidencePresent = true;
        if (Float.isFinite(confidence) && confidence >= 0.0f && confidence <= 1.0f) {
            confidenceSum += confidence;
            acceptedDetections++;
            latestConfidence = confidence;
        }

        if (action.esRiesgo()) {
            actionCandidate = null;
            report(ViolationType.CONTACTO_RIESGO,
                "Contacto visible con una superficie marcada como riesgo; repita el procedimiento",
                currentAction == null ? action.name() : currentAction.name());
            restartAttempt("CONTACTO_RIESGO");
            return response();
        }

        if (currentAction == null) {
            actionCandidate = null;
            if (action != OmsAction.MOJAR_MANOS) {
                report(ViolationType.ACCION_OMS_FUERA_DE_SECUENCIA,
                    "El protocolo OMS debe comenzar por mojar ambas manos",
                    action.name());
                return response();
            }
            beginAttempt(receivedAtMs);
            return response();
        }

        if (action == currentAction) {
            actionCandidate = null;
            if (receivedAtMs > lastDetectionMs
                && currentActionObservations < MIN_PHASE_OBSERVATIONS) {
                currentActionObservations++;
            }
            accrueCurrentAction(receivedAtMs);
            if (currentAction.permiteEvidenciaJabon()) collectSoapEvidence(evidence, receivedAtMs);
            if (currentAction == OmsAction.CERRAR_GRIFO_CON_TOALLA
                && phaseHasEnoughEvidence(currentAction)) {
                if (getTotalActiveMs() >= minimumTotalMs) {
                    status = HandwashingSessionState.COMPLETADA;
                    finishedAtMs = System.currentTimeMillis();
                    finishedAtMonotonicNanos = System.nanoTime();
                    clearCurrentVisualEvidence();
                } else {
                    report(ViolationType.DURACION_OMS_INSUFICIENTE,
                        "La duración activa observada no alcanzó " + minimumTotalMs
                            + " ms; repita el procedimiento completo desde mojar las manos",
                        currentAction.name());
                    restartAttempt("DURACION_OMS_INSUFICIENTE");
                }
            }
            return response();
        }

        ActionCandidate candidate = confirmActionCandidate(action, evidence, receivedAtMs);
        if (candidate == null) return response();

        if (action == currentAction.siguiente()) {
            // The first of two distinct observations closes the previous phase;
            // only after confirmation is the candidate credited to the new one.
            accrueCurrentAction(candidate.firstObservedAtMs());
            if (!phaseHasEnoughEvidence(currentAction)) {
                report(ViolationType.FASE_OMS_DEMASIADO_CORTA,
                    "La fase " + currentAction.getNombre() + " no tuvo observación continua suficiente; reinicie desde mojar las manos",
                    currentAction.name());
                restartAttempt("FASE_OMS_DEMASIADO_CORTA_" + currentAction.name());
                return response();
            }
            if (currentAction.getOrden() >= OmsAction.FROTAR_PALMAS.getOrden()
                && currentAction.getOrden() <= OmsAction.FROTAR_PUNTAS_DE_DEDOS.getOrden()
                && !hasSoapCoverageForAction(currentAction)) {
                report(ViolationType.COBERTURA_JABON_INCOMPLETA,
                    "No se confirmó jabón visible en ambas manos durante " + currentAction.getNombre()
                        + "; repita desde mojar las manos",
                    currentAction.name());
                restartAttempt("COBERTURA_JABON_INCOMPLETA");
                return response();
            }
            if (currentAction == OmsAction.FROTAR_PUNTAS_DE_DEDOS && !hasFullSoapCoverage()) {
                report(ViolationType.COBERTURA_JABON_INCOMPLETA,
                    "Antes de enjuagar no se confirmó espuma en las 12 regiones de ambas manos; repita desde mojar las manos",
                    currentAction.name());
                restartAttempt("COBERTURA_JABON_INCOMPLETA");
                return response();
            }
            activateCandidateAction(candidate, receivedAtMs, evidence);
            return response();
        }

        report(ViolationType.ACCION_OMS_FUERA_DE_SECUENCIA,
            "Se detectó " + action.getNombre() + " fuera de orden; repita desde mojar las manos",
            action.name());
        restartAttempt("ACCION_OMS_FUERA_DE_SECUENCIA_" + action.name());
        return response();
    }

    private void beginAttempt(long timestampMs) {
        currentAttempt++;
        attemptStartMonotonicNanos = System.nanoTime();
        currentAttemptInfractions.clear();
        currentAttemptLastInfractionMonotonicNanos = 0L;
        activeMsByAction.clear();
        currentAction = OmsAction.MOJAR_MANOS;
        currentActionObservations = 1;
        activeMsByAction.put(currentAction, 0L);
        lastDetectionMs = timestampMs;
        status = HandwashingSessionState.EN_PROGRESO;
        resetSoapCoverage();
    }

    /**
     * Requires two distinct, consecutive observations within the camera-gap window.
     * A single transient label cannot advance or restart the WHO sequence.
     */
    private ActionCandidate confirmActionCandidate(
        OmsAction action, Map<String, SoapEvidence> evidence, long timestampMs
    ) {
        ActionCandidate pending = actionCandidate;
        if (pending == null || pending.action() != action) {
            actionCandidate = new ActionCandidate(action, timestampMs, copyEvidence(evidence));
            return null;
        }

        long interval = timestampMs - pending.firstObservedAtMs();
        if (interval <= 0L) return null;
        if (interval > maxDetectionGapMs) {
            actionCandidate = new ActionCandidate(action, timestampMs, copyEvidence(evidence));
            return null;
        }

        actionCandidate = null;
        return pending;
    }

    private void activateCandidateAction(
        ActionCandidate candidate, long confirmedAtMs, Map<String, SoapEvidence> confirmedEvidence
    ) {
        OmsAction action = candidate.action();
        currentAction = action;
        activeMsByAction.putIfAbsent(action, 0L);
        long confirmedInterval = confirmedAtMs - candidate.firstObservedAtMs();
        if (confirmedInterval > 0L && confirmedInterval <= maxDetectionGapMs) {
            activeMsByAction.merge(action, confirmedInterval, Long::sum);
        }
        currentActionObservations = MIN_PHASE_OBSERVATIONS;
        lastDetectionMs = confirmedAtMs;
        if (action.permiteEvidenciaJabon()) {
            collectSoapEvidence(candidate.firstEvidence(), candidate.firstObservedAtMs());
            collectSoapEvidence(confirmedEvidence, confirmedAtMs);
        }
    }

    private static Map<String, SoapEvidence> copyEvidence(Map<String, SoapEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return Map.of();
        Map<String, SoapEvidence> copy = new LinkedHashMap<>();
        evidence.forEach((region, item) -> {
            if (region == null || item == null) return;
            SoapEvidence snapshot = new SoapEvidence();
            snapshot.setEstado(item.getEstado());
            snapshot.setConfianza(item.getConfianza());
            copy.put(region, snapshot);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private void accrueCurrentAction(long timestampMs) {
        if (lastDetectionMs > 0L && timestampMs >= lastDetectionMs) {
            long delta = timestampMs - lastDetectionMs;
            if (delta <= maxDetectionGapMs) activeMsByAction.merge(currentAction, delta, Long::sum);
        }
        lastDetectionMs = timestampMs;
    }

    private boolean phaseHasEnoughEvidence(OmsAction action) {
        return currentActionObservations >= MIN_PHASE_OBSERVATIONS
            && activeMsByAction.getOrDefault(action, 0L) >= minimumObservationPerPhaseMs;
    }

    private void collectSoapEvidence(Map<String, SoapEvidence> evidence, long timestampMs) {
        if (evidence == null) return;
        evidence.forEach((rawRegion, item) -> {
            SoapRegion region = SoapRegion.from(rawRegion);
            Float confidence = item == null ? null : item.getConfianza();
            if (region == null || item == null || item.getEstado() == null
                || region.getAccionVerificacion() != currentAction
                || confidence == null || !Float.isFinite(confidence)
                || confidence < MIN_SOAP_REGION_CONFIDENCE || confidence > 1.0f) return;

            if (item.getEstado() == SoapEvidenceStatus.ESPUMA_VISIBLE) {
                long previousAt = lastFoamObservationMs.getOrDefault(region, 0L);
                int count = previousAt > 0L && timestampMs > previousAt
                    && timestampMs - previousAt <= maxDetectionGapMs
                    ? foamObservations.getOrDefault(region, 0) + 1 : 1;
                foamObservations.put(region, count);
                lastFoamObservationMs.put(region, timestampMs);
                soapCoverage.put(region, count >= REQUIRED_SOAP_REGION_OBSERVATIONS
                    ? SoapEvidenceStatus.ESPUMA_VISIBLE : SoapEvidenceStatus.NO_VERIFICABLE);
            } else if (item.getEstado() == SoapEvidenceStatus.SIN_ESPUMA_VISIBLE) {
                foamObservations.put(region, 0);
                lastFoamObservationMs.put(region, timestampMs);
                soapCoverage.put(region, SoapEvidenceStatus.SIN_ESPUMA_VISIBLE);
            } else {
                // Contradictory/unclear evidence revokes earlier visual credit.
                foamObservations.put(region, 0);
                lastFoamObservationMs.remove(region);
                soapCoverage.put(region, SoapEvidenceStatus.NO_VERIFICABLE);
            }
        });
        coverageComplete = hasFullSoapCoverage();
    }

    private boolean hasFullSoapCoverage() {
        return java.util.Arrays.stream(SoapRegion.values()).allMatch(region ->
            soapCoverage.get(region) == SoapEvidenceStatus.ESPUMA_VISIBLE);
    }

    private boolean hasSoapCoverageForAction(OmsAction action) {
        return java.util.Arrays.stream(SoapRegion.values())
            .filter(region -> region.getAccionVerificacion() == action)
            .allMatch(region -> soapCoverage.get(region) == SoapEvidenceStatus.ESPUMA_VISIBLE);
    }

    private void resetSoapCoverage() {
        foamObservations.clear();
        lastFoamObservationMs.clear();
        soapCoverage.clear();
        for (SoapRegion region : SoapRegion.values()) {
            soapCoverage.put(region, SoapEvidenceStatus.NO_VERIFICABLE);
        }
        coverageComplete = false;
    }

    private void report(ViolationType type, String detail, String actionName) {
        String wireActionCode = canonicalActionCode(actionName);
        long nowEpochMs = System.currentTimeMillis();
        long nowMonotonicNanos = System.nanoTime();
        Violation infraction = new Violation(
            type, detail, wireActionCode, Instant.ofEpochMilli(nowEpochMs).toString());
        currentInfraction = infraction;

        Violation lastInAttempt = currentAttemptInfractions.isEmpty()
            ? null : currentAttemptInfractions.get(currentAttemptInfractions.size() - 1);
        boolean activeAttempt = currentAction != null && currentAttempt > 0;
        boolean duplicateInAttempt = activeAttempt && sameInfraction(
            lastInAttempt, type, detail, wireActionCode)
            && nowMonotonicNanos - currentAttemptLastInfractionMonotonicNanos
                < TimeUnit.SECONDS.toNanos(2L);
        if (activeAttempt && !duplicateInAttempt) {
            if (currentAttemptInfractions.size() >= MAX_ATTEMPT_INFRACTIONS) {
                currentAttemptInfractions.remove(0);
            }
            currentAttemptInfractions.add(infraction);
            currentAttemptLastInfractionMonotonicNanos = nowMonotonicNanos;
        }

        Violation last = infractions.isEmpty() ? null : infractions.get(infractions.size() - 1);
        boolean duplicateInSession = sameInfraction(last, type, detail, wireActionCode)
            && nowMonotonicNanos - lastInfractionMonotonicNanos < TimeUnit.SECONDS.toNanos(2L);
        if (duplicateInSession) return;
        if (infractions.size() >= MAX_INFRACTIONS) {
            infractions.remove(0);
            omittedInfractions++;
        }
        infractions.add(infraction);
        lastInfractionMonotonicNanos = nowMonotonicNanos;
    }

    private static String canonicalActionCode(String value) {
        OmsAction action = OmsAction.fromClaseModelo(value);
        return action == null ? value : action.getClaseModelo();
    }

    private static boolean sameInfraction(
        Violation previous, ViolationType type, String detail, String actionName
    ) {
        return previous != null && previous.getTipo() == type
            && java.util.Objects.equals(previous.getPaso(), actionName)
            && java.util.Objects.equals(previous.getDetalle(), detail);
    }

    public synchronized void registrarError(ViolationType type, String detail, String actionName) {
        markActivity();
        report(type, detail, actionName);
    }

    /** An uncreditable frame breaks the entire experimental WHO attempt fail-closed. */
    public synchronized void descartarObservacion(String detail) {
        if (status == HandwashingSessionState.COMPLETADA || status == HandwashingSessionState.EXPIRADA) return;
        actionCandidate = null;
        latestConfidence = 0.0f;
        handEvidencePresent = false;
        if (currentAction == null) {
            lastDetectionMs = 0L;
            return;
        }
        String reason = detail == null || detail.isBlank()
            ? "Se rechazó una observación no acreditable; reinicie desde mojar las manos"
            : detail;
        report(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA, reason, currentAction.name());
        restartAttempt("OBSERVACION_NO_ACREDITABLE");
    }

    public synchronized void reiniciarIntento(String reason) {
        restartAttempt(reason);
    }

    private void restartAttempt(String reason) {
        // A repeated invalid frame after a reset is not a new failed attempt.
        // Only archive/count an attempt while its state machine is in progress.
        if (currentAction != null && currentAttempt > 0) {
            lastRetryError = currentInfraction;
            Map<String, Long> times = new LinkedHashMap<>();
            activeMsByAction.forEach((action, milliseconds) -> times.put(action.getClaseModelo(), milliseconds));
            HandwashingAttemptSummary attempt = new HandwashingAttemptSummary(
                currentAttempt,
                "REINICIADO",
                reason,
                failedAttemptDurationMs(),
                times,
                List.copyOf(currentAttemptInfractions)
            );
            if (attempts.size() >= MAX_ATTEMPTS) attempts.remove(0);
            attempts.add(attempt);
            retries++;
        }
        currentAction = null;
        actionCandidate = null;
        currentActionObservations = 0;
        status = HandwashingSessionState.ESPERANDO_INICIO;
        activeMsByAction.clear();
        lastDetectionMs = 0L;
        lastObservationMs = 0L;
        attemptStartMonotonicNanos = 0L;
        currentAttemptInfractions.clear();
        resetSoapCoverage();
        clearCurrentVisualEvidence();
    }

    private long failedAttemptDurationMs() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - attemptStartMonotonicNanos));
    }

    private void clearCurrentVisualEvidence() {
        latestConfidence = 0.0f;
        handEvidencePresent = false;
    }

    private HandwashingStatusResponse response() {
        HandwashingStatusResponse response = new HandwashingStatusResponse();
        response.setSessionId(sessionId);
        response.setMessageType("STATE_UPDATE");
        response.setModoEvaluacion("PROTOCOLO_OMS");
        response.setClaseCandidata(actionCandidate == null ? null : actionCandidate.action().getClaseModelo());
        response.setConfianzaCandidata(actionCandidate == null ? null : latestConfidence);
        response.setEstadoActual(currentAction == null ? null : currentAction.getClaseModelo());
        response.setEstadoSesion(status.name());
        response.setConfianzaDeteccion(latestConfidence);
        response.setManoDetectada(handEvidencePresent);
        response.setProgreso(new Progress(stepsCompleted(), OmsAction.SECUENCIA.size()));
        response.setTiempoAcumuladoMs(currentAction == null ? 0L : activeMsByAction.getOrDefault(currentAction, 0L));
        response.setInfraccion(currentInfraction);
        // Keep live updates small; the complete bounded history belongs to
        // the terminal session summary.
        response.setIntentosReiniciados(retries);
        response.setUltimoErrorReinicio(lastRetryError);
        response.setCoberturaJabon(getCoverageStates());
        response.setCoberturaJabonCompleta(coverageComplete);
        response.setProcedimientoCompletoValidado(isProcedureValidated());
        response.setTiempoTotalActivoMs(getTotalActiveMs());
        response.setDuracionMinimaObjetivoMs(minimumTotalMs);
        return response;
    }

    public synchronized HandwashingStatusResponse getEstadoActualResponse() { return response(); }

    private int stepsCompleted() {
        if (status == HandwashingSessionState.COMPLETADA) return OmsAction.SECUENCIA.size();
        return currentAction == null ? 0 : currentAction.getOrden() - 1;
    }

    public synchronized Map<String, String> getCoverageStates() {
        Map<String, String> result = new LinkedHashMap<>();
        for (SoapRegion region : SoapRegion.values()) {
            result.put(region.name(), soapCoverage.getOrDefault(region, SoapEvidenceStatus.NO_VERIFICABLE).name());
        }
        return result;
    }

    public synchronized boolean isProcedureValidated() {
        return modelReady && status == HandwashingSessionState.COMPLETADA && coverageComplete;
    }

    public synchronized List<String> getMissingActions() {
        if (isProcedureValidated()) return List.of();
        List<String> missing = new ArrayList<>();
        if (!modelReady) missing.add("MODELO_OMS_NO_VALIDADO");
        if (!coverageComplete) missing.add("COBERTURA_JABON_NO_CONFIRMADA_EN_12_REGIONES");
        int completed = stepsCompleted();
        OmsAction.SECUENCIA.stream().filter(action -> action.getOrden() > completed)
            .forEach(action -> missing.add(action.getClaseModelo()));
        return List.copyOf(missing);
    }

    public synchronized Map<String, Long> getActiveMsByAction() {
        Map<String, Long> result = new LinkedHashMap<>();
        activeMsByAction.forEach((action, milliseconds) -> result.put(action.getClaseModelo(), milliseconds));
        return java.util.Collections.unmodifiableMap(result);
    }

    public synchronized long getTotalActiveMs() {
        return activeMsByAction.values().stream().mapToLong(Long::longValue).sum();
    }

    public synchronized double getAverageConfidence() {
        return acceptedDetections == 0 ? 0.0 : confidenceSum / acceptedDetections;
    }

    public synchronized boolean isSoapCoverageComplete() { return coverageComplete; }
    public synchronized OmsAction getCurrentAction() { return currentAction; }
    public synchronized HandwashingSessionState getStatus() { return status; }
    public synchronized Violation getCurrentInfraction() { return currentInfraction; }
    public synchronized List<Violation> getInfractions() { return List.copyOf(infractions); }
    public synchronized List<Violation> getCurrentAttemptInfractions() { return List.copyOf(currentAttemptInfractions); }
    public synchronized List<HandwashingAttemptSummary> getAttempts() { return List.copyOf(attempts); }
    public synchronized int getCurrentAttempt() { return currentAttempt; }
    public synchronized int getRetries() { return retries; }
    public synchronized int getStepsCompleted() { return stepsCompleted(); }
    public synchronized int getOmittedInfractions() { return omittedInfractions; }
    public synchronized long getMinimumTotalMs() { return minimumTotalMs; }
    public synchronized long getLastActivityMs() { return lastActivityMs; }
    public synchronized long getTiempoInactivoMs(long nowEpochMs, long nowMonotonicNanos) {
        long wallElapsedMs = Math.max(0L, nowEpochMs - lastActivityMs);
        long monotonicElapsedMs = Math.max(0L,
            TimeUnit.NANOSECONDS.toMillis(nowMonotonicNanos - lastActivityMonotonicNanos));
        return Math.max(wallElapsedMs, monotonicElapsedMs);
    }
    public synchronized long getDurationMs() {
        long finishedAt = finishedAtMs > 0L
            ? finishedAtMonotonicNanos : System.nanoTime();
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(finishedAt - sessionStartMonotonicNanos));
    }
    public synchronized boolean isInactiveSince(long nowEpochMs, long nowMonotonicNanos, long timeoutMs) {
        return status != HandwashingSessionState.COMPLETADA && status != HandwashingSessionState.EXPIRADA
            && getTiempoInactivoMs(nowEpochMs, nowMonotonicNanos) >= timeoutMs;
    }
    public synchronized void expire() {
        if (status != HandwashingSessionState.COMPLETADA && status != HandwashingSessionState.EXPIRADA) {
            if (currentAction != null && currentAttempt > 0) {
                report(ViolationType.EVIDENCIA_VISUAL_INTERRUPTA,
                    "La sesión expiró por inactividad durante " + currentAction.getNombre()
                        + "; repita desde mojar las manos",
                    currentAction.name());
                restartAttempt("SESION_EXPIRADA_POR_INACTIVIDAD");
            }
            status = HandwashingSessionState.EXPIRADA;
            actionCandidate = null;
            currentInfraction = null;
            finishedAtMs = System.currentTimeMillis();
            finishedAtMonotonicNanos = System.nanoTime();
            lastActivityMs = finishedAtMs;
            lastActivityMonotonicNanos = finishedAtMonotonicNanos;
            clearCurrentVisualEvidence();
        }
    }

    private void markActivity() {
        lastActivityMs = System.currentTimeMillis();
        lastActivityMonotonicNanos = System.nanoTime();
    }
}
