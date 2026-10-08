package com.handwash.service;

import com.handwash.model.DeteccionEvento;
import com.handwash.model.AccionOms;
import com.handwash.model.PasoLavado;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns producer protocol versions, process epochs and per-epoch sequence
 * watermarks. SessionManager calls these operations while holding the owning
 * session monitor, so the producer envelope and wash-state transition remain
 * one atomic per-session operation.
 */
public final class ProducerProtocolRegistry {
    /** Exact v2 wire labels emitted by the current camera producer. */
    private static final Set<String> CANONICAL_DETECTION_CLASSES = buildCanonicalDetectionClasses();

    private static final class StreamState {
        private boolean epochRequired;
        private boolean rotationPending;
        private String activeEpoch;
        private long lastAcceptedFrameSequence = -1L;
        private long lastAcceptedControlSequence = -1L;
        private long lastAcceptedPresenceFrameSequence = -1L;
    }

    public record EpochRegistration(String producerEpoch, int protocolVersion, boolean rotated) {}

    /** Rejection plus optional diagnostic age from the currently registered epoch. */
    public record Assessment(HandwashMetrics.ProducerRejectionReason rejectionReason,
                             Long matchingCaptureAgeMs) {
        public boolean acceptedEnvelope() { return rejectionReason == null; }
    }

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    public void initializeSession(String sessionId, boolean epochRequired) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId es obligatorio para inicializar el productor");
        }
        StreamState state = new StreamState();
        state.epochRequired = epochRequired;
        streams.put(sessionId, state);
    }

    /** Enables strict v2 and invalidates an already active producer epoch. */
    public void requireEpoch(String sessionId) {
        StreamState state = streams.computeIfAbsent(sessionId, ignored -> new StreamState());
        synchronized (state) {
            state.epochRequired = true;
            if (state.activeEpoch != null) {
                state.activeEpoch = null;
                state.lastAcceptedFrameSequence = -1L;
                state.lastAcceptedControlSequence = -1L;
                state.lastAcceptedPresenceFrameSequence = -1L;
                state.rotationPending = true;
            }
        }
    }

    public boolean isEpochRequired(String sessionId) {
        StreamState state = streams.get(sessionId);
        if (state == null) return false;
        synchronized (state) {
            return state.epochRequired;
        }
    }

    public EpochRegistration registerEpoch(String sessionId) {
        StreamState state = streams.computeIfAbsent(sessionId, ignored -> new StreamState());
        synchronized (state) {
            boolean rotated = state.activeEpoch != null || state.rotationPending;
            state.epochRequired = true;
            state.rotationPending = false;
            state.activeEpoch = UUID.randomUUID().toString();
            state.lastAcceptedFrameSequence = -1L;
            state.lastAcceptedControlSequence = -1L;
            state.lastAcceptedPresenceFrameSequence = -1L;
            return new EpochRegistration(state.activeEpoch, 2, rotated);
        }
    }

    /**
     * Validates and reserves a v2 envelope atomically. Once the producer,
     * canonical class and frame/evidence sequence relationship are valid, the
     * frame is consumed before domain validation. A stale pose, invalid
     * timestamp, or other rejected observation therefore cannot be repaired
     * and replayed under the same frame sequence.
     */
    public Assessment assessAndReserve(String sessionId, DeteccionEvento event) {
        StreamState state = streams.get(sessionId);
        if (state == null) {
            return new Assessment(hasV2Fields(event)
                ? HandwashMetrics.ProducerRejectionReason.VERSIONED_FIELDS_ON_LEGACY_SESSION
                : null, null);
        }
        synchronized (state) {
            Long diagnosticAge = null;
            if (state.epochRequired && state.activeEpoch != null
                && state.activeEpoch.equals(event.getProducerEpoch())
                && event.getCaptureAgeMs() != null && event.getCaptureAgeMs() >= 0L) {
                diagnosticAge = event.getCaptureAgeMs();
            }
            HandwashMetrics.ProducerRejectionReason rejection = rejectionReason(state, event);
            if (rejection == null && state.epochRequired) {
                if ("CONTROL".equals(event.getEventType())
                    || "PRESENCE".equals(event.getEventType())) {
                    state.lastAcceptedControlSequence = event.getControlSequence();
                    if ("CONTROL".equals(event.getEventType())) {
                        state.lastAcceptedFrameSequence = Math.max(
                            state.lastAcceptedFrameSequence, event.getFrameWatermark());
                    } else {
                        state.lastAcceptedPresenceFrameSequence = event.getFrameWatermark();
                    }
                } else {
                    state.lastAcceptedFrameSequence = event.getFrameSequence();
                }
            }
            return new Assessment(rejection, diagnosticAge);
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) streams.remove(sessionId);
    }

    private HandwashMetrics.ProducerRejectionReason rejectionReason(
        StreamState state, DeteccionEvento event) {
        if (!state.epochRequired) {
            return hasV2Fields(event)
                ? HandwashMetrics.ProducerRejectionReason.VERSIONED_FIELDS_ON_LEGACY_SESSION
                : null;
        }
        if (state.activeEpoch == null) {
            return HandwashMetrics.ProducerRejectionReason.EPOCH_NOT_REGISTERED;
        }
        if (event.getProducerEpoch() == null || event.getProducerEpoch().isBlank()) {
            return HandwashMetrics.ProducerRejectionReason.EPOCH_REQUIRED;
        }
        if (!state.activeEpoch.equals(event.getProducerEpoch())) {
            return HandwashMetrics.ProducerRejectionReason.EPOCH_MISMATCH;
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            return HandwashMetrics.ProducerRejectionReason.EVENT_TYPE_REQUIRED;
        }
        if ("CONTROL".equals(event.getEventType())) {
            if (!"FONDO".equals(event.getClaseDetectada())
                && !"OMS_SIN_EVIDENCIA".equals(event.getClaseDetectada())) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_CLASS_INVALID;
            }
            if (event.getFrameSequence() != null || event.getEvidenciaMovimiento() != null
                || (event.getEvidenciaJabon() != null && !event.getEvidenciaJabon().isEmpty())
                || event.getEvidenciaJabonSecuencia() != null
                || event.getPresenceHandsVisible() != null) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_CARRIES_FRAME_EVIDENCE;
            }
            Long controlSequence = event.getControlSequence();
            if (controlSequence == null) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_REQUIRED;
            }
            if (controlSequence < 0L) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_INVALID;
            }
            Long frameWatermark = event.getFrameWatermark();
            if (frameWatermark == null) {
                return HandwashMetrics.ProducerRejectionReason.FRAME_WATERMARK_REQUIRED;
            }
            if (frameWatermark < 0L) {
                return HandwashMetrics.ProducerRejectionReason.FRAME_WATERMARK_INVALID;
            }
            if (frameWatermark < Math.max(state.lastAcceptedFrameSequence,
                    state.lastAcceptedPresenceFrameSequence)) {
                return HandwashMetrics.ProducerRejectionReason.FRAME_WATERMARK_OUT_OF_ORDER;
            }
            if (controlSequence == state.lastAcceptedControlSequence) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_DUPLICATE;
            }
            if (controlSequence < state.lastAcceptedControlSequence) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_OUT_OF_ORDER;
            }
            if (event.getCaptureAgeMs() != null && event.getCaptureAgeMs() < 0L) {
                return HandwashMetrics.ProducerRejectionReason.CAPTURE_AGE_INVALID;
            }
            return null;
        }
        if ("PRESENCE".equals(event.getEventType())) {
            if (!"PRESENCIA_MANOS".equals(event.getClaseDetectada())) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_CLASS_INVALID;
            }
            if (event.getFrameSequence() != null || event.getEvidenciaMovimiento() != null
                || (event.getEvidenciaJabon() != null && !event.getEvidenciaJabon().isEmpty())
                || event.getEvidenciaJabonSecuencia() != null) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_CARRIES_DOMAIN_EVIDENCE;
            }
            Integer hands = event.getPresenceHandsVisible();
            if (hands == null || hands < 0 || hands > 2) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_HAND_COUNT_INVALID;
            }
            if (event.getControlSequence() == null) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_REQUIRED;
            }
            if (event.getControlSequence() < 0L) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_INVALID;
            }
            if (event.getFrameWatermark() == null) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_REQUIRED;
            }
            if (event.getFrameWatermark() < 0L) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_INVALID;
            }
            if (event.getFrameWatermark() < state.lastAcceptedPresenceFrameSequence) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_OUT_OF_ORDER;
            }
            if (event.getFrameWatermark() == state.lastAcceptedPresenceFrameSequence) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_DUPLICATE;
            }
            if (event.getFrameWatermark() < state.lastAcceptedFrameSequence) {
                return HandwashMetrics.ProducerRejectionReason.PRESENCE_FRAME_SEQUENCE_OUT_OF_ORDER;
            }
            if (event.getControlSequence() == state.lastAcceptedControlSequence) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_DUPLICATE;
            }
            if (event.getControlSequence() < state.lastAcceptedControlSequence) {
                return HandwashMetrics.ProducerRejectionReason.CONTROL_SEQUENCE_OUT_OF_ORDER;
            }
            if (event.getCaptureAgeMs() != null && event.getCaptureAgeMs() < 0L) {
                return HandwashMetrics.ProducerRejectionReason.CAPTURE_AGE_INVALID;
            }
            if (event.getTimestamp() == null || event.getTimestamp().isBlank()
                || event.getConfianza() == null || !Float.isFinite(event.getConfianza())
                || event.getConfianza() < 0.0f || event.getConfianza() > 1.0f) {
                return HandwashMetrics.ProducerRejectionReason.EVENT_TYPE_INVALID;
            }
            return null;
        }
        if (!"DETECTION".equals(event.getEventType())) {
            return HandwashMetrics.ProducerRejectionReason.EVENT_TYPE_INVALID;
        }
        if (!CANONICAL_DETECTION_CLASSES.contains(event.getClaseDetectada())) {
            return HandwashMetrics.ProducerRejectionReason.NON_CANONICAL_CLASS;
        }
        if (event.getControlSequence() != null || event.getFrameWatermark() != null
            || event.getPresenceHandsVisible() != null) {
            return HandwashMetrics.ProducerRejectionReason.EVENT_TYPE_INVALID;
        }
        Long sequence = event.getFrameSequence();
        if (sequence == null) {
            return HandwashMetrics.ProducerRejectionReason.FRAME_SEQUENCE_REQUIRED;
        }
        if (sequence < 0L) {
            return HandwashMetrics.ProducerRejectionReason.FRAME_SEQUENCE_INVALID;
        }
        if (sequence < state.lastAcceptedPresenceFrameSequence) {
            return HandwashMetrics.ProducerRejectionReason.FRAME_SEQUENCE_OUT_OF_ORDER;
        }
        if (event.getCaptureAgeMs() != null && event.getCaptureAgeMs() < 0L) {
            return HandwashMetrics.ProducerRejectionReason.CAPTURE_AGE_INVALID;
        }
        boolean hasSoapEvidence = event.getEvidenciaJabon() != null
            && !event.getEvidenciaJabon().isEmpty();
        if (!hasSoapEvidence && event.getEvidenciaJabonSecuencia() != null) {
            return HandwashMetrics.ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_WITHOUT_EVIDENCE;
        }
        if (hasSoapEvidence && event.getEvidenciaJabonSecuencia() == null) {
            return HandwashMetrics.ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_REQUIRED;
        }
        if (hasSoapEvidence && !sequence.equals(event.getEvidenciaJabonSecuencia())) {
            return HandwashMetrics.ProducerRejectionReason.SOAP_EVIDENCE_SEQUENCE_MISMATCH;
        }
        if (event.getEvidenciaMovimiento() != null
            && !sequence.equals(event.getEvidenciaMovimiento().secuencia())) {
            return HandwashMetrics.ProducerRejectionReason.EVIDENCE_SEQUENCE_MISMATCH;
        }
        if (sequence == state.lastAcceptedFrameSequence) {
            return HandwashMetrics.ProducerRejectionReason.FRAME_SEQUENCE_DUPLICATE;
        }
        if (sequence < state.lastAcceptedFrameSequence) {
            return HandwashMetrics.ProducerRejectionReason.FRAME_SEQUENCE_OUT_OF_ORDER;
        }
        return null;
    }

    private boolean hasV2Fields(DeteccionEvento event) {
        return event.getProducerEpoch() != null || event.getEventType() != null
            || event.getFrameSequence() != null || event.getControlSequence() != null
            || event.getFrameWatermark() != null || event.getCaptureAgeMs() != null
            || event.getPresenceHandsVisible() != null;
    }

    private static Set<String> buildCanonicalDetectionClasses() {
        Set<String> classes = new java.util.HashSet<>();
        Arrays.stream(PasoLavado.values())
            .filter(step -> step != PasoLavado.FONDO)
            .map(Enum::name)
            .forEach(classes::add);
        Arrays.stream(AccionOms.values())
            .filter(action -> !action.esSinEvidencia())
            .map(AccionOms::getClaseModelo)
            .forEach(classes::add);
        return Set.copyOf(classes);
    }
}
