package com.handwash.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.handwash.model.PasoLavado;
import com.handwash.model.TipoProtocolo;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Low-cardinality timing and outcome metrics for the local camera pipeline. */
@Component
public final class HandwashMetrics {
    private static final long MAX_RECORDED_CAPTURE_AGE_MS = TimeUnit.DAYS.toMillis(1);

    public enum ExpirationCause {
        IDLE_TIMEOUT("idle_timeout"),
        PIPELINE_FAILURE("pipeline_failure");

        private final String tag;
        ExpirationCause(String tag) { this.tag = tag; }
    }

    public enum SendFailureCause {
        ASYNC_CALLBACK("async_callback"),
        SYNC_EXCEPTION("sync_exception"),
        TIMEOUT("timeout");

        private final String tag;
        SendFailureCause(String tag) { this.tag = tag; }
    }

    public enum PersistenceFailureCause {
        WRITE("write"),
        DELETE("delete"),
        RETENTION_CLEANUP("retention_cleanup");

        private final String tag;
        PersistenceFailureCause(String tag) { this.tag = tag; }
    }

    public enum ProducerRejectionReason {
        VERSIONED_FIELDS_ON_LEGACY_SESSION("versioned_fields_on_legacy_session"),
        EPOCH_REQUIRED("epoch_required"),
        EPOCH_NOT_REGISTERED("epoch_not_registered"),
        EPOCH_MISMATCH("epoch_mismatch"),
        FRAME_SEQUENCE_REQUIRED("frame_sequence_required"),
        FRAME_SEQUENCE_INVALID("frame_sequence_invalid"),
        FRAME_SEQUENCE_DUPLICATE("frame_sequence_duplicate"),
        FRAME_SEQUENCE_OUT_OF_ORDER("frame_sequence_out_of_order"),
        EVIDENCE_SEQUENCE_MISMATCH("evidence_sequence_mismatch"),
        SOAP_EVIDENCE_SEQUENCE_REQUIRED("soap_evidence_sequence_required"),
        SOAP_EVIDENCE_SEQUENCE_MISMATCH("soap_evidence_sequence_mismatch"),
        SOAP_EVIDENCE_SEQUENCE_WITHOUT_EVIDENCE("soap_evidence_sequence_without_evidence"),
        EVENT_TYPE_REQUIRED("event_type_required"),
        EVENT_TYPE_INVALID("event_type_invalid"),
        CONTROL_SEQUENCE_REQUIRED("control_sequence_required"),
        CONTROL_SEQUENCE_INVALID("control_sequence_invalid"),
        CONTROL_SEQUENCE_DUPLICATE("control_sequence_duplicate"),
        CONTROL_SEQUENCE_OUT_OF_ORDER("control_sequence_out_of_order"),
        FRAME_WATERMARK_REQUIRED("frame_watermark_required"),
        FRAME_WATERMARK_INVALID("frame_watermark_invalid"),
        FRAME_WATERMARK_OUT_OF_ORDER("frame_watermark_out_of_order"),
        CONTROL_CARRIES_FRAME_EVIDENCE("control_carries_frame_evidence"),
        CONTROL_CLASS_INVALID("control_class_invalid"),
        PRESENCE_CLASS_INVALID("presence_class_invalid"),
        PRESENCE_HAND_COUNT_INVALID("presence_hand_count_invalid"),
        PRESENCE_FRAME_SEQUENCE_REQUIRED("presence_frame_sequence_required"),
        PRESENCE_FRAME_SEQUENCE_INVALID("presence_frame_sequence_invalid"),
        PRESENCE_FRAME_SEQUENCE_DUPLICATE("presence_frame_sequence_duplicate"),
        PRESENCE_FRAME_SEQUENCE_OUT_OF_ORDER("presence_frame_sequence_out_of_order"),
        PRESENCE_CARRIES_DOMAIN_EVIDENCE("presence_carries_domain_evidence"),
        NON_CANONICAL_CLASS("non_canonical_class"),
        CAPTURE_AGE_INVALID("capture_age_invalid");

        private final String tag;
        ProducerRejectionReason(String tag) { this.tag = tag; }
    }

    /** Fixed categories used to diagnose detection evidence without high-cardinality labels. */
    public enum IntentRejectionReason {
        MISSING_OR_STALE_EVIDENCE("sin_evidencia_reciente"),
        INCOMPLETE_FRAMING("encuadre_incompleto"),
        UNVERIFIABLE_SPATIAL_EVIDENCE("evidencia_espacial_no_verificable"),
        NO_START_MOTION("movimiento_inicial_no_detectado"),
        NO_ACTIVE_MOTION("movimiento_activo_no_detectado"),
        PRESENCE_WARMUP_INCOMPLETE("presence_warmup_incomplete"),
        NO_WASH_GESTURE("sin_gesto_de_lavado"),
        START_WITH_PALMS_REQUIRED("inicie_con_palmas"),
        LOW_START_CONFIDENCE("gesto_incierto"),
        OTHER("otro");

        private final String tag;
        IntentRejectionReason(String tag) { this.tag = tag; }

        static IntentRejectionReason fromCode(String code) {
            if (code == null) return null;
            return switch (code) {
                case "SIN_EVIDENCIA_RECIENTE" -> MISSING_OR_STALE_EVIDENCE;
                case "ENCUADRE_INCOMPLETO" -> INCOMPLETE_FRAMING;
                case "EVIDENCIA_ESPACIAL_NO_VERIFICABLE" -> UNVERIFIABLE_SPATIAL_EVIDENCE;
                case "MOVIMIENTO_INICIAL_NO_DETECTADO" -> NO_START_MOTION;
                case "MOVIMIENTO_ACTIVO_NO_DETECTADO" -> NO_ACTIVE_MOTION;
                case "PRESENCIA_NO_ESTABILIZADA" -> PRESENCE_WARMUP_INCOMPLETE;
                case "SIN_GESTO_DE_LAVADO" -> NO_WASH_GESTURE;
                case "INICIE_CON_PALMAS" -> START_WITH_PALMS_REQUIRED;
                case "GESTO_INCIERTO" -> LOW_START_CONFIDENCE;
                default -> OTHER;
            };
        }
    }

    private final MeterRegistry registry;
    private final Timer validationValid;
    private final Timer validationInvalid;
    private final Timer sessionLockWait;
    private final Timer stateProcessing;
    private final Timer strategyProcessing;
    private final Timer pipelineProcessing;
    private final Timer expirationDetectionDelay;
    private final Timer websocketPendingEnqueue;
    private final Timer websocketMailboxEnqueue;
    private final Timer websocketSend;
    private final Timer terminalDelivery;
    private final Timer producerCaptureAge;
    private final Counter transportAccepted;
    private final Counter transportFiltered;
    private final Counter producerEpochRegistrations;
    private final Counter producerEpochRotations;
    private final Map<ProducerRejectionReason, Counter> producerRejections =
        new EnumMap<>(ProducerRejectionReason.class);
    private final Map<IntentRejectionReason, Counter> intentRejections =
        new EnumMap<>(IntentRejectionReason.class);
    private final Counter outboundQueueRejected;
    private final Counter coalescedStates;
    private final Map<ExpirationCause, Counter> expirations = new EnumMap<>(ExpirationCause.class);
    private final Map<SendFailureCause, Counter> sendFailures = new EnumMap<>(SendFailureCause.class);
    private final Map<PersistenceFailureCause, Counter> failedAttemptPersistenceFailures =
        new EnumMap<>(PersistenceFailureCause.class);
    private final Map<TipoProtocolo, Counter> ruleAccepted = new EnumMap<>(TipoProtocolo.class);
    private final Map<TipoProtocolo, Counter> ruleRejected = new EnumMap<>(TipoProtocolo.class);
    private final Map<TipoProtocolo, Map<PasoLavado, Counter>> ruleStepAccepted =
        new EnumMap<>(TipoProtocolo.class);
    private final Map<TipoProtocolo, Map<PasoLavado, Counter>> ruleStepRejected =
        new EnumMap<>(TipoProtocolo.class);

    @Autowired
    public HandwashMetrics(MeterRegistry registry) {
        this.registry = registry;
        validationValid = timer("handwash.detection.http.validation", "result", "valid");
        validationInvalid = timer("handwash.detection.http.validation", "result", "invalid");
        sessionLockWait = timer("handwash.detection.session.lock.wait");
        stateProcessing = timer("handwash.detection.rules.state");
        strategyProcessing = timer("handwash.detection.rules.strategy");
        pipelineProcessing = timer("handwash.detection.pipeline");
        expirationDetectionDelay = timer("handwash.session.expiration.detection.delay");
        websocketPendingEnqueue = timer("handwash.websocket.pending.enqueue");
        websocketMailboxEnqueue = timer("handwash.websocket.mailbox.enqueue");
        websocketSend = timer("handwash.websocket.send");
        terminalDelivery = timer("handwash.websocket.terminal.delivery");
        producerCaptureAge = timer("handwash.producer.capture.age");
        transportAccepted = counter("handwash.detection.transport.disposition", "result", "accepted");
        transportFiltered = counter("handwash.detection.transport.disposition", "result", "filtered");
        producerEpochRegistrations = counter("handwash.producer.epoch.registrations");
        producerEpochRotations = counter("handwash.producer.epoch.rotations");
        for (ProducerRejectionReason reason : ProducerRejectionReason.values()) {
            producerRejections.put(reason,
                counter("handwash.producer.rejections", "reason", reason.tag));
        }
        for (IntentRejectionReason reason : IntentRejectionReason.values()) {
            intentRejections.put(reason,
                counter("handwash.detection.intent.rejections", "reason", reason.tag));
        }
        outboundQueueRejected = counter("handwash.websocket.executor.queue.rejected");
        coalescedStates = counter("handwash.websocket.state.coalesced");
        for (ExpirationCause cause : ExpirationCause.values()) {
            expirations.put(cause, counter("handwash.session.expirations", "cause", cause.tag));
        }
        for (SendFailureCause cause : SendFailureCause.values()) {
            sendFailures.put(cause, counter("handwash.websocket.send.failures", "cause", cause.tag));
        }
        for (PersistenceFailureCause cause : PersistenceFailureCause.values()) {
            failedAttemptPersistenceFailures.put(cause,
                counter("handwash.failed-attempt.persistence.failures", "operation", cause.tag));
        }
        for (TipoProtocolo protocol : TipoProtocolo.values()) {
            ruleAccepted.put(protocol, counter("handwash.detection.rule.decisions",
                "protocol", protocol.name(), "result", "accepted"));
            ruleRejected.put(protocol, counter("handwash.detection.rule.decisions",
                "protocol", protocol.name(), "result", "rejected"));

            Map<PasoLavado, Counter> acceptedByStep = new EnumMap<>(PasoLavado.class);
            Map<PasoLavado, Counter> rejectedByStep = new EnumMap<>(PasoLavado.class);
            for (PasoLavado step : PasoLavado.values()) {
                acceptedByStep.put(step, counter("handwash.detection.rule.step.decisions",
                    "protocol", protocol.name(), "step", step.name(), "result", "accepted"));
                rejectedByStep.put(step, counter("handwash.detection.rule.step.decisions",
                    "protocol", protocol.name(), "step", step.name(), "result", "rejected"));
            }
            ruleStepAccepted.put(protocol, acceptedByStep);
            ruleStepRejected.put(protocol, rejectedByStep);
        }
    }

    private HandwashMetrics() {
        registry = null;
        validationValid = null;
        validationInvalid = null;
        sessionLockWait = null;
        stateProcessing = null;
        strategyProcessing = null;
        pipelineProcessing = null;
        expirationDetectionDelay = null;
        websocketPendingEnqueue = null;
        websocketMailboxEnqueue = null;
        websocketSend = null;
        terminalDelivery = null;
        producerCaptureAge = null;
        transportAccepted = null;
        transportFiltered = null;
        producerEpochRegistrations = null;
        producerEpochRotations = null;
        for (IntentRejectionReason reason : IntentRejectionReason.values()) {
            intentRejections.put(reason, null);
        }
        outboundQueueRejected = null;
        coalescedStates = null;
        for (TipoProtocolo protocol : TipoProtocolo.values()) {
            ruleAccepted.put(protocol, null);
            ruleRejected.put(protocol, null);
        }
    }

    /** Used by isolated unit fixtures that do not have Spring's MeterRegistry. */
    public static HandwashMetrics noop() { return new HandwashMetrics(); }

    private Timer timer(String name, String... tags) {
        if (registry == null) return null;
        return Timer.builder(name)
            .publishPercentiles(0.50, 0.95, 0.99)
            .tags(tags)
            .register(registry);
    }

    private Counter counter(String name, String... tags) {
        return registry == null ? null : Counter.builder(name).tags(tags).register(registry);
    }

    public void recordHttpValidation(long elapsedNanos, boolean valid) {
        record(valid ? validationValid : validationInvalid, elapsedNanos);
    }

    public void recordSessionLockWait(long elapsedNanos) { record(sessionLockWait, elapsedNanos); }
    public void recordStateProcessing(long elapsedNanos) { record(stateProcessing, elapsedNanos); }
    public void recordStrategyProcessing(long elapsedNanos) { record(strategyProcessing, elapsedNanos); }
    public void recordPipelineProcessing(long elapsedNanos) { record(pipelineProcessing, elapsedNanos); }
    public void recordExpirationDetectionDelay(long elapsedMillis) {
        if (expirationDetectionDelay != null) {
            expirationDetectionDelay.record(Math.max(0L, elapsedMillis), TimeUnit.MILLISECONDS);
        }
    }
    public void recordTerminalDelivery(long elapsedNanos) { record(terminalDelivery, elapsedNanos); }
    public void recordWebsocketPendingEnqueue(long elapsedNanos) {
        record(websocketPendingEnqueue, elapsedNanos);
    }
    public void recordWebsocketMailboxEnqueue(long elapsedNanos) {
        record(websocketMailboxEnqueue, elapsedNanos);
    }

    public void recordTransportDisposition(boolean accepted) {
        increment(accepted ? transportAccepted : transportFiltered);
    }

    public void recordProducerEpochRegistration(boolean rotated) {
        increment(producerEpochRegistrations);
        if (rotated) increment(producerEpochRotations);
    }

    public void recordProducerRejection(ProducerRejectionReason reason) {
        if (registry != null) increment(producerRejections.get(reason));
    }

    /** Records only a fixed category; arbitrary input can never become a metric tag. */
    public void recordIntentRejection(String reasonCode) {
        if (registry == null) return;
        IntentRejectionReason reason = IntentRejectionReason.fromCode(reasonCode);
        if (reason != null) increment(intentRejections.get(reason));
    }

    /**
     * The capture age is producer-supplied diagnostic data, never a session clock.
     * Bound only the metric sample so a malformed/outlier value cannot poison
     * percentiles; this never changes protocol or wash-step acceptance.
     */
    public void recordProducerCaptureAge(long ageMillis) {
        if (producerCaptureAge != null) {
            long boundedAgeMillis = Math.min(MAX_RECORDED_CAPTURE_AGE_MS, Math.max(0L, ageMillis));
            producerCaptureAge.record(boundedAgeMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void recordExpiration(ExpirationCause cause) {
        if (registry != null) expirations.get(cause).increment();
    }

    public void recordSendFailure(SendFailureCause cause) {
        if (registry != null) sendFailures.get(cause).increment();
    }

    public void recordFailedAttemptPersistenceFailure(PersistenceFailureCause cause) {
        if (registry != null && cause != null) {
            increment(failedAttemptPersistenceFailures.get(cause));
        }
    }

    public void recordRuleDecision(TipoProtocolo protocol, boolean accepted) {
        if (registry == null || protocol == null) return;
        increment(accepted ? ruleAccepted.get(protocol) : ruleRejected.get(protocol));
    }

    /** Records the aggregate decision and its fixed-enum step breakdown. */
    public void recordRuleDecision(TipoProtocolo protocol, PasoLavado step, boolean accepted) {
        recordRuleDecision(protocol, accepted);
        if (registry == null || protocol == null || step == null) return;
        Map<TipoProtocolo, Map<PasoLavado, Counter>> counters =
            accepted ? ruleStepAccepted : ruleStepRejected;
        increment(counters.get(protocol).get(step));
    }

    public void recordOutboundQueueRejected() { increment(outboundQueueRejected); }
    public void recordCoalescedState() { increment(coalescedStates); }

    public Timer.Sample startWebsocketSend() {
        return registry == null ? null : Timer.start(registry);
    }

    public void stopWebsocketSend(Timer.Sample sample) {
        if (sample != null && websocketSend != null) sample.stop(websocketSend);
    }

    public void bindOutboundExecutor(ThreadPoolExecutor executor) {
        if (registry == null) return;
        Gauge.builder("handwash.websocket.executor.queue.size", executor,
                pool -> pool.getQueue().size())
            .description("Current bounded WebSocket sender task queue depth")
            .register(registry);
        Gauge.builder("handwash.websocket.executor.active", executor,
                ThreadPoolExecutor::getActiveCount)
            .description("Currently active WebSocket sender tasks")
            .register(registry);
    }

    private void record(Timer timer, long elapsedNanos) {
        if (timer != null) timer.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private void increment(Counter counter) {
        if (counter != null) counter.increment();
    }
}
