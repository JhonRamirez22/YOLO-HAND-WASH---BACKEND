package com.handwash.service;

import com.handwash.agent.Receiver;
import com.handwash.intention.HandPresenceWarmup;
import com.handwash.model.DetectionEvent;
import com.handwash.model.OmsAction;
import com.handwash.model.MovementEvidence;
import com.handwash.model.HandPoseEvidence;
import com.handwash.model.HandwashingStep;
import com.handwash.model.HandwashingSession;
import com.handwash.model.ProtocolType;
import com.handwash.model.ViolationType;
import com.handwash.observer.DetectionPipelineException;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.security.SessionCredentialRegistry;
import com.handwash.security.SessionPairingCodeRegistry;
import com.handwash.service.persistence.FailedAttemptPersistenceCoordinator;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import jakarta.annotation.PostConstruct;

@Service
public class SessionManager {
    private static final int MAX_SUPPORTED_SESSIONS = 4_096;

    public enum DetectionOutcome {
        ACCEPTED, FILTERED, TRANSPORT_REJECTED, INVALID, UNAUTHORIZED,
        NOT_FOUND, TERMINAL
    }

    public record DetectionResult(DetectionOutcome outcome, DetectionEvent event,
                                  HandwashingSession session, String detail,
                                  HandwashMetrics.ProducerRejectionReason rejectionReason) {}

    public record ProducerEpochRegistration(String producerEpoch, int protocolVersion,
                                            boolean rotated) {}

    public enum ProducerEpochOutcome { REGISTERED, UNAUTHORIZED, NOT_FOUND, TERMINAL }

    public record ProducerEpochResult(ProducerEpochOutcome outcome,
                                      ProducerEpochRegistration registration) {}

    public enum AccessRole { OWNER, DEVICE, VIEWER, LOCAL }

    public record AccessInfo(AccessRole role, Long expiresAtEpochMs,
                             Long expiresAtMonotonicNanos, Long credentialRevision) {}

    public record TokenIssue(String value, long expiresAtEpochMs) {}

    private final Receiver receptor;
    private final ValidationRuleStrategyFactory strategyFactory;
    private final FailedAttemptPersistenceCoordinator failedAttemptPersistence;
    private final HandwashMetrics metrics;
    private final OpenCvHandMotionEstimator handMotionEstimator;
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private final Map<String, HandwashingSession> sesiones = new ConcurrentHashMap<>();
    private final Map<String, HandPresenceWarmup> handPresenceWarmups = new ConcurrentHashMap<>();
    private final ProducerProtocolRegistry producerProtocolRegistry = new ProducerProtocolRegistry();
    private final SessionPairingCodeRegistry pairingCodeRegistry = new SessionPairingCodeRegistry();
    /** TTL enforcement must not be extended by wall-clock corrections. */
    private LongSupplier monotonicNanos = System::nanoTime;
    @Value("${handwash.session.access-token-ttl-ms:86400000}")
    private long accessTokenTtlMs = SessionCredentialRegistry.MAX_ACCESS_TOKEN_TTL_MS;
    private LongSupplier epochMillis = System::currentTimeMillis;
    private final SessionCredentialRegistry credentialRegistry = new SessionCredentialRegistry(
        () -> monotonicNanos.getAsLong(), () -> epochMillis.getAsLong());
    @Value("${handwash.session.access-required:true}")
    private boolean accessRequired = true;
    @Value("${handwash.session.timeout-minutes:5}")
    private long timeoutMinutes = 5L;
    @Value("${handwash.session.terminal-retention-minutes:30}")
    private long terminalRetentionMinutes = 30L;
    @Value("${handwash.session.max-detection-gap-ms:1500}")
    private long maxDetectionGapMs = 1500L;
    @Value("${handwash.session.step-transition-confirm-max-gap-ms:650}")
    private long maxStepTransitionConfirmGapMs = 650L;
    @Value("${handwash.intention.hand-presence-warmup-ms:3000}")
    private long handPresenceWarmupMs = 3_000L;
    @Value("${handwash.intention.hand-presence-max-gap-ms:500}")
    private long handPresenceMaxGapMs = 500L;
    @Value("${handwash.session.max-sessions:128}")
    private int maxSessions = 128;
    @Value("${handwash.session.max-active-sessions:128}")
    private int maxActiveSessions = 128;
    @Value("${handwash.oms.model-ready:false}")
    private boolean omsModelReady;
    @Value("${handwash.oms.input-enabled:false}")
    private boolean omsInputEnabled;

    public SessionManager(Receiver receptor) {
        this(receptor, new ValidationRuleStrategyFactory(), null, HandwashMetrics.noop());
    }

    public SessionManager(Receiver receptor, ValidationRuleStrategyFactory strategyFactory) {
        this(receptor, strategyFactory, null, HandwashMetrics.noop());
    }

    public SessionManager(Receiver receptor, ValidationRuleStrategyFactory strategyFactory,
                          FailedAttemptStore failedAttemptRepository) {
        this(receptor, strategyFactory, failedAttemptRepository, HandwashMetrics.noop());
    }

    public SessionManager(Receiver receptor, ValidationRuleStrategyFactory strategyFactory,
                          FailedAttemptStore failedAttemptRepository, HandwashMetrics metrics) {
        this(receptor, strategyFactory, failedAttemptRepository, metrics,
            new OpenCvHandMotionEstimator());
    }

    @Autowired
    public SessionManager(Receiver receptor, ValidationRuleStrategyFactory strategyFactory,
                          FailedAttemptStore failedAttemptRepository, HandwashMetrics metrics,
                          OpenCvHandMotionEstimator handMotionEstimator) {
        this.receptor = receptor;
        this.strategyFactory = strategyFactory;
        this.metrics = metrics;
        this.handMotionEstimator = handMotionEstimator;
        this.failedAttemptPersistence = new FailedAttemptPersistenceCoordinator(
            failedAttemptRepository, this::getSesion, metrics);
    }

    @PostConstruct
    void validarConfiguracionRuntime() {
        credentialRegistry.configureTtl(accessTokenTtlMs);
        if (maxSessions < 1 || maxSessions > MAX_SUPPORTED_SESSIONS) {
            throw new IllegalStateException("handwash.session.max-sessions debe estar entre 1 y "
                + MAX_SUPPORTED_SESSIONS);
        }
        if (maxActiveSessions < 1) {
            throw new IllegalStateException("handwash.session.max-active-sessions debe ser al menos 1");
        }
        if (maxActiveSessions > maxSessions) {
            throw new IllegalStateException(
                "handwash.session.max-active-sessions no puede superar handwash.session.max-sessions");
        }
        if (timeoutMinutes < 1) {
            throw new IllegalStateException("handwash.session.timeout-minutes debe ser al menos 1");
        }
        if (terminalRetentionMinutes < 1) {
            throw new IllegalStateException(
                "handwash.session.terminal-retention-minutes debe ser al menos 1");
        }
        if (maxDetectionGapMs < 100L || maxDetectionGapMs > 5_000L) {
            throw new IllegalStateException(
                "handwash.session.max-detection-gap-ms debe estar entre 100 y 5000");
        }
        if (maxStepTransitionConfirmGapMs < 100L || maxStepTransitionConfirmGapMs > 1_500L) {
            throw new IllegalStateException(
                "handwash.session.step-transition-confirm-max-gap-ms debe estar entre 100 y 1500");
        }
        if (handPresenceWarmupMs < 0L || handPresenceWarmupMs > 10_000L) {
            throw new IllegalStateException(
                "handwash.intention.hand-presence-warmup-ms debe estar entre 0 y 10000");
        }
        if (handPresenceMaxGapMs < 100L || handPresenceMaxGapMs > 1_000L) {
            throw new IllegalStateException(
                "handwash.intention.hand-presence-max-gap-ms debe estar entre 100 y 1000");
        }
    }

    public synchronized String crearSesion(ProtocolType protocolo) {
        return crearSesion(protocolo, false);
    }

    public synchronized String crearSesion(ProtocolType protocolo, boolean requireProducerEpoch) {
        if (sesiones.size() >= maxSessions) throw new SessionCapacityException();
        long sesionesActivas = sesiones.values().stream().filter(this::esActiva).count();
        if (sesionesActivas >= maxActiveSessions) throw new SessionCapacityException();
        String sessionId = UUID.randomUUID().toString();
        HandwashingSession sesion = new HandwashingSession(
            sessionId, protocolo, strategyFactory.crear(protocolo), maxDetectionGapMs,
            maxStepTransitionConfirmGapMs, omsModelReady);
        pairingCodeRegistry.register(sessionId);
        credentialRegistry.configureTtl(accessTokenTtlMs);
        credentialRegistry.createCredentials(sessionId);
        sesiones.put(sessionId, sesion);
        producerProtocolRegistry.initializeSession(sessionId, requireProducerEpoch);
        handPresenceWarmups.put(sessionId,
            new HandPresenceWarmup(handPresenceWarmupMs, handPresenceMaxGapMs));
        return sessionId;
    }

    /** Enables strict producer-envelope checks without changing a running v2 epoch. */
    public boolean requireProducerEpoch(String sessionId) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null) return false;
        synchronized (sesion) {
            if (sesiones.get(sessionId) != sesion || !esActiva(sesion)) return false;
            // Pairing a replacement v2 producer closes the old epoch before
            // credentials are returned; no old-process frame can bridge the gap.
            producerProtocolRegistry.requireEpoch(sessionId);
            handMotionEstimator.resetSession(sessionId);
            HandPresenceWarmup warmup = handPresenceWarmups.get(sessionId);
            if (warmup != null) warmup.reset();
            return true;
        }
    }

    public boolean producerEpochRequired(String sessionId) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null) return false;
        synchronized (sesion) {
            return sesiones.get(sessionId) == sesion
                && producerProtocolRegistry.isEpochRequired(sessionId);
        }
    }

    /** Authenticates and registers atomically with device-token rotation for this session. */
    public ProducerEpochResult registrarProducerEpoch(String sessionId, String accessToken) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null) {
            return new ProducerEpochResult(ProducerEpochOutcome.NOT_FOUND, null);
        }
        synchronized (sesion) {
            if (sesiones.get(sessionId) != sesion) {
                return new ProducerEpochResult(ProducerEpochOutcome.NOT_FOUND, null);
            }
            if (!tieneAcceso(sessionId, accessToken, false)) {
                return new ProducerEpochResult(ProducerEpochOutcome.UNAUTHORIZED, null);
            }
            if (!esActiva(sesion)) {
                return new ProducerEpochResult(ProducerEpochOutcome.TERMINAL, null);
            }
            ProducerProtocolRegistry.EpochRegistration registration =
                producerProtocolRegistry.registerEpoch(sessionId);
            handMotionEstimator.resetSession(sessionId);
            HandPresenceWarmup warmup = handPresenceWarmups.get(sessionId);
            if (warmup != null) warmup.reset();
            metrics.recordProducerEpochRegistration(registration.rotated());
            return new ProducerEpochResult(ProducerEpochOutcome.REGISTERED,
                new ProducerEpochRegistration(registration.producerEpoch(),
                    registration.protocolVersion(), registration.rotated()));
        }
    }

    /** Solo se puede emparejar automáticamente cuando la elección no es ambigua. */
    public HandwashingSession getSesionActiva() {
        List<HandwashingSession> activas = getSesionesActivas();
        return activas.size() == 1 ? activas.get(0) : null;
    }

    public List<HandwashingSession> getSesionesActivas() {
        return sesiones.values().stream().filter(this::esActiva).toList();
    }

    public String getCodigoEmparejamiento(String sessionId) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null || !esActiva(sesion)) return null;
        return pairingCodeRegistry.displayCode(sessionId);
    }

    public HandwashingSession getSesionPorCodigo(String codigo) {
        if (codigo == null) return null;
        String sessionId = pairingCodeRegistry.findSessionId(codigo);
        HandwashingSession sesion = sessionId == null ? null : sesiones.get(sessionId);
        return sesion != null && esActiva(sesion) ? sesion : null;
    }

    public boolean isAccessRequired() {
        return accessRequired;
    }

    public String getOwnerToken(String sessionId) {
        return credentialRegistry.getOwnerToken(sessionId);
    }

    public String getDeviceToken(String sessionId) {
        return credentialRegistry.getDeviceToken(sessionId);
    }

    public long getOwnerTokenExpirationEpochMs(String sessionId) {
        return credentialRegistry.getOwnerTokenExpirationEpochMs(sessionId);
    }

    public long getDeviceTokenExpirationEpochMs(String sessionId) {
        return credentialRegistry.getDeviceTokenExpirationEpochMs(sessionId);
    }

    /** Issues a fresh one-day device capability after a valid pairing-code login. */
    public TokenIssue emitirTokenDispositivo(String sessionId) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null) return null;
        synchronized (sesion) {
            if (sesiones.get(sessionId) != sesion || !esActiva(sesion)) return null;
            SessionCredentialRegistry.TokenIssue issued = credentialRegistry.rotateDeviceToken(sessionId);
            return issued == null ? null : new TokenIssue(issued.value(), issued.expiresAtEpochMs());
        }
    }

    /** Issues a read-only dashboard capability without changing producer credentials or epochs. */
    public TokenIssue emitirTokenDashboard(String sessionId) {
        HandwashingSession sesion = sesiones.get(sessionId);
        if (sesion == null) return null;
        synchronized (sesion) {
            if (sesiones.get(sessionId) != sesion || !esActiva(sesion)) return null;
            SessionCredentialRegistry.TokenIssue issued = credentialRegistry.rotateViewerToken(sessionId);
            return issued == null ? null : new TokenIssue(issued.value(), issued.expiresAtEpochMs());
        }
    }

    /** Returns the authenticated capability role only while its fixed expiry is in the future. */
    public AccessInfo autenticar(String sessionId, String token) {
        if (sessionId == null || !sesiones.containsKey(sessionId)) return null;
        if (!accessRequired) return new AccessInfo(AccessRole.LOCAL, null, null, null);
        SessionCredentialRegistry.Access access = credentialRegistry.authenticate(sessionId, token);
        if (access == null) return null;
        AccessRole role = switch (access.role()) {
            case OWNER -> AccessRole.OWNER;
            case DEVICE -> AccessRole.DEVICE;
            case VIEWER -> AccessRole.VIEWER;
        };
        return new AccessInfo(role, access.expiresAtEpochMs(),
            access.expiresAtMonotonicNanos(), access.credentialRevision());
    }

    /** Checks whether a long-lived consumer still represents the current credential. */
    public boolean credencialVigente(String sessionId, AccessInfo access) {
        if (sessionId == null || access == null || !sesiones.containsKey(sessionId)) return false;
        if (access.role() == AccessRole.LOCAL) return !accessRequired;
        if (access.credentialRevision() == null) return false;
        SessionCredentialRegistry.Role role = switch (access.role()) {
            case OWNER -> SessionCredentialRegistry.Role.OWNER;
            case DEVICE -> SessionCredentialRegistry.Role.DEVICE;
            case VIEWER -> SessionCredentialRegistry.Role.VIEWER;
            case LOCAL -> null;
        };
        if (role == null) return false;
        return credentialRegistry.isCurrent(sessionId, role, access.credentialRevision());
    }

    /** Producer capability: only OWNER/DEVICE tokens may affect the wash or replace its epoch. */
    public boolean tieneAcceso(String sessionId, String token, boolean soloPropietario) {
        if (sessionId == null || !sesiones.containsKey(sessionId)) return false;
        if (!accessRequired) return true;
        AccessInfo acceso = autenticar(sessionId, token);
        boolean producer = acceso != null
            && (acceso.role() == AccessRole.OWNER || acceso.role() == AccessRole.DEVICE);
        return producer && (!soloPropietario || acceso.role() == AccessRole.OWNER);
    }

    /** Read-only capability for session snapshots and failure summaries. */
    public boolean tieneAccesoLectura(String sessionId, String token) {
        if (sessionId == null || !sesiones.containsKey(sessionId)) return false;
        if (!accessRequired) return true;
        AccessInfo acceso = autenticar(sessionId, token);
        return acceso != null && acceso.role() != AccessRole.LOCAL;
    }

    private boolean esActiva(HandwashingSession sesion) {
        synchronized (sesion) {
            com.handwash.model.HandwashingSessionState estado = sesion.getEstadoSesion();
            return estado != com.handwash.model.HandwashingSessionState.COMPLETADA
                && estado != com.handwash.model.HandwashingSessionState.EXPIRADA;
        }
    }

    public HandwashingSession getSesion(String sessionId) {
        return sesiones.get(sessionId);
    }

    public boolean eliminarSesion(String sessionId) {
        if (sessionId == null) return true;
        return failedAttemptPersistence.deleteSession(sessionId, () -> {
            HandwashingSession sesion = sesiones.get(sessionId);
            if (sesion != null) {
                synchronized (sesion) {
                    sesiones.remove(sessionId, sesion);
                }
            }
            // Preserve persistence serialization, but never hold the hot-path
            // session monitor during disk I/O. A camera request that already
            // captured this session can acquire the monitor, observe removal,
            // and return without waiting for the database delete.
            pairingCodeRegistry.remove(sessionId);
            credentialRegistry.removeCredentials(sessionId);
            producerProtocolRegistry.removeSession(sessionId);
            handMotionEstimator.removeSession(sessionId);
            handPresenceWarmups.remove(sessionId);
        });
    }

    public Map<String, HandwashingSession> getSesiones() {
        return Map.copyOf(sesiones);
    }

    /** Snapshot para iteraciones de notificadores y métricas sin exponer la vista concurrente. */
    public Map<String, HandwashingSession> getSesionesSnapshot() {
        return new HashMap<>(sesiones);
    }

    public DetectionEvent procesarDeteccion(String sessionId, String claseDetectada, float confianza) {
        return procesarDeteccion(new DetectionEvent(
            sessionId, claseDetectada, confianza, java.time.Instant.now().toString()));
    }

    public String validarDeteccion(DetectionEvent evento) {
        return receptor.validarEstructura(evento);
    }

    public DetectionEvent procesarDeteccion(DetectionEvent evento) {
        return procesarDeteccionInterna(evento, false, null, false).event();
    }

    /** Internal compatibility entry point for callers that already validated the structure. */
    public DetectionEvent procesarDeteccionValidada(DetectionEvent evento) {
        return procesarDeteccionInterna(evento, true, null, false).event();
    }

    /** HTTP camera path: authenticate, validate producer ordering and process under one session lock. */
    public DetectionResult procesarDeteccionHttp(DetectionEvent evento, String accessToken) {
        return procesarDeteccionInterna(evento, false, accessToken, true);
    }

    private DetectionResult procesarDeteccionInterna(DetectionEvent evento,
                                                      boolean estructuraValidada,
                                                      String accessToken,
                                                      boolean requireAuthorization) {
        if (evento == null || evento.getSessionId() == null || evento.getSessionId().isBlank()) {
            String error = estructuraValidada ? "sessionId es obligatorio" : validarYMedir(evento);
            return new DetectionResult(DetectionOutcome.INVALID, null, null, error, null);
        }
        HandwashingSession sesion = sesiones.get(evento.getSessionId());
        if (sesion == null) {
            return new DetectionResult(DetectionOutcome.NOT_FOUND, null, null, null, null);
        }
        // El orden de State -> Strategy -> Observer debe ser atómico por sesión.
        long lockWaitStarted = System.nanoTime();
        synchronized (sesion) {
            metrics.recordSessionLockWait(System.nanoTime() - lockWaitStarted);
            if (sesiones.get(evento.getSessionId()) != sesion) {
                return new DetectionResult(DetectionOutcome.NOT_FOUND, null, null, null, null);
            }
            if (requireAuthorization
                && !tieneAcceso(evento.getSessionId(), accessToken, false)) {
                return new DetectionResult(DetectionOutcome.UNAUTHORIZED, null, sesion, null, null);
            }
            if (sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.EXPIRADA
                || sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.COMPLETADA) {
                return new DetectionResult(DetectionOutcome.TERMINAL, null, sesion, null, null);
            }

            // The producer envelope is checked and its frame watermark reserved
            // before evaluating domain evidence. This prevents a canonical frame
            // with stale/invalid evidence from being repaired and replayed under
            // the same sequence. Malformed producer envelopes (epoch, class,
            // ordering or evidence-sequence mismatch) are rejected without
            // advancing the watermark.
            ProducerProtocolRegistry.Assessment producerAssessment =
                producerProtocolRegistry.assessAndReserve(evento.getSessionId(), evento);
            if (producerAssessment.matchingCaptureAgeMs() != null) {
                // Record same-epoch traffic (including replayed/out-of-order
                // frames) as diagnostics only; this never controls acceptance.
                metrics.recordProducerCaptureAge(producerAssessment.matchingCaptureAgeMs());
            }
            HandwashMetrics.ProducerRejectionReason producerRejection =
                producerAssessment.rejectionReason();
            if (producerRejection != null) {
                metrics.recordProducerRejection(producerRejection);
                return new DetectionResult(DetectionOutcome.TRANSPORT_REJECTED, null,
                    sesion, producerRejection.name(), producerRejection);
            }

            if ("PRESENCE".equals(evento.getEventType())) {
                evento.setEvidenciaPoseManos(null);
                if (!Integer.valueOf(2).equals(evento.getPresenceHandsVisible())) {
                    handMotionEstimator.resetSession(evento.getSessionId());
                }
                HandPresenceWarmup warmup = handPresenceWarmups.get(evento.getSessionId());
                if (warmup == null) {
                    return new DetectionResult(DetectionOutcome.INVALID, null, sesion,
                        "La compuerta de presencia no está inicializada", null);
                }
                warmup.observe(evento.getFrameWatermark(), evento.getPresenceHandsVisible(),
                    evento.serverIngressAtMonotonicNanosOr(monotonicNanos.getAsLong()),
                    sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.EN_PROGRESO);
                // Transport ACK only: this pulse never enters State, Strategy or Observer.
                return new DetectionResult(DetectionOutcome.ACCEPTED, evento, sesion, null, null);
            }

            if ("CONTROL".equals(evento.getEventType()) || isSpatialResetSignal(evento)) {
                handMotionEstimator.resetSession(evento.getSessionId());
            }
            boolean strictProducer = producerProtocolRegistry.isEpochRequired(evento.getSessionId());
            String requestedMode = evento.getAccionOmsResuelta() != null
                ? "PROTOCOLO_OMS" : "FRICCION_PARCIAL";
            if ("PROTOCOLO_OMS".equals(requestedMode) && !omsInputEnabled) {
                evento.setEvidenciaPoseManos(null);
                throw new IncompatibleDetectionModeException(requestedMode,
                    "La ingestión OMS está deshabilitada en este perfil: el modelo y la evidencia no están aprobados");
            }
            String validationError = estructuraValidada ? null : validarYMedir(evento);
            if (validationError != null) {
                // Invalid camera observations can occur repeatedly while pose
                // evidence is occluded/stale. The HTTP response and validation
                // timer already expose the rejection; keep routine frame-level
                // noise out of production WARN logs.
                log.debug("Detección inválida rechazada: {}", validationError);
                // A malformed authenticated observation cannot bridge the last
                // credited interval or retain votes for a future transition.
                sesion.descartarDeteccion(evento,
                    "Observación inválida; se interrumpen votos y tiempo acreditable");
                // In the experimental OMS flow, rejecting an observation can
                // fail-close and archive the active attempt. Mark persistence
                // here as well as on accepted events so a quiet camera does
                // not leave that summary only in memory until shutdown.
                evento.setEvidenciaPoseManos(null);
                if (strictProducer && requiresBilateralMotionEvidence(evento)) {
                    handMotionEstimator.resetSession(evento.getSessionId());
                }
                failedAttemptPersistence.markPending(sesion);
                notificarCambioEstado(sesion);
                return new DetectionResult(DetectionOutcome.INVALID, null, sesion,
                    validationError, null);
            }
            if (!sesion.admiteModo(requestedMode)) {
                throw new IncompatibleDetectionModeException(sesion.getModoEvaluacion());
            }
            HandPresenceWarmup presenceWarmup = handPresenceWarmups.get(evento.getSessionId());
            boolean serverPresenceReady = handPresenceWarmupMs == 0L
                || (presenceWarmup != null && presenceWarmup.permitsStep(
                    monotonicNanos.getAsLong(),
                    sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.EN_PROGRESO));
            if (producerProtocolRegistry.isEpochRequired(evento.getSessionId())
                && !isIndependentRiskAlert(evento) && !serverPresenceReady) {
                metrics.recordIntentRejection("PRESENCIA_NO_ESTABILIZADA");
                handMotionEstimator.resetSession(evento.getSessionId());
                evento.setEvidenciaPoseManos(null);
                sesion.descartarDeteccion(evento,
                    String.format(Locale.ROOT,
                        "El servidor aún no confirma %d ms continuos con ambas manos visibles",
                        handPresenceWarmupMs));
                notificarCambioEstado(sesion);
                // The v2 envelope is valid and consumed; accepted remains a transport ACK,
                // not evidence that the step was credited by the clinical state machine.
                return new DetectionResult(DetectionOutcome.ACCEPTED, evento, sesion, null, null);
            }
            if (strictProducer && requiresBilateralMotionEvidence(evento)) {
                String spatialError = recalculateMovementEvidence(evento);
                if (spatialError != null) {
                    metrics.recordIntentRejection("EVIDENCIA_ESPACIAL_NO_VERIFICABLE");
                    evento.setEvidenciaMovimiento(null);
                    evento.setEvidenciaPoseManos(null);
                    sesion.descartarDeteccion(evento, spatialError);
                    failedAttemptPersistence.markPending(sesion);
                    notificarCambioEstado(sesion);
                    return new DetectionResult(DetectionOutcome.FILTERED, null, sesion,
                        spatialError, null);
                }
            } else {
                // Pose coordinates are request-local and never reach observers, responses, or storage.
                evento.setEvidenciaPoseManos(null);
            }
            long pipelineStarted = System.nanoTime();
            try {
                DetectionEvent procesado = receptor.recibirValidada(evento);
                if (procesado != null) {
                    failedAttemptPersistence.markPending(sesion);
                    if (!esActiva(sesion)) handMotionEstimator.resetSession(evento.getSessionId());
                    return new DetectionResult(DetectionOutcome.ACCEPTED, procesado, sesion,
                        null, null);
                }
                else {
                    sesion.descartarDeteccion(evento);
                    // A confidence-filtered OMS observation can also restart
                    // and archive an active attempt.
                    failedAttemptPersistence.markPending(sesion);
                    notificarCambioEstado(sesion);
                }
                return new DetectionResult(DetectionOutcome.FILTERED, null, sesion, null, null);
            } catch (DetectionPipelineException error) {
                sesion.registrarInfraccion(ViolationType.ERROR_PROCESAMIENTO,
                    "La evaluación no pudo completarse; reinicia la sesión", null);
                sesion.expirar();
                metrics.recordExpiration(HandwashMetrics.ExpirationCause.PIPELINE_FAILURE);
                failedAttemptPersistence.markPending(sesion);
                notificarCambioEstado(sesion);
                throw error;
            } finally {
                metrics.recordPipelineProcessing(System.nanoTime() - pipelineStarted);
            }
        }
    }

    private String validarYMedir(DetectionEvent evento) {
        long started = System.nanoTime();
        String error = receptor.validarEstructura(evento);
        metrics.recordHttpValidation(System.nanoTime() - started, error == null);
        return error;
    }

    private boolean isIndependentRiskAlert(DetectionEvent evento) {
        var action = evento.getAccionOmsResuelta();
        return action != null && action.esRiesgo();
    }

    private boolean requiresBilateralMotionEvidence(DetectionEvent evento) {
        if (!"DETECTION".equals(evento.getEventType())) return false;
        HandwashingStep step = evento.getPasoLavadoResuelto();
        if (step != null) return step != HandwashingStep.FONDO;
        OmsAction action = evento.getAccionOmsResuelta();
        return action != null && !action.esRiesgo() && !action.esSinEvidencia();
    }

    private boolean isSpatialResetSignal(DetectionEvent evento) {
        HandwashingStep step = evento.getPasoLavadoResuelto();
        if (step == HandwashingStep.FONDO) return true;
        OmsAction action = evento.getAccionOmsResuelta();
        return action != null && (action.esRiesgo() || action.esSinEvidencia());
    }

    private String recalculateMovementEvidence(DetectionEvent evento) {
        MovementEvidence producerEvidence = evento.getEvidenciaMovimiento();
        HandPoseEvidence pose = evento.getEvidenciaPoseManos();
        Long frameSequence = evento.getFrameSequence();
        if (producerEvidence == null || pose == null || frameSequence == null
            || !frameSequence.equals(producerEvidence.secuencia())
            || !producerEvidence.esReciente() || !pose.isStructurallyValid()) {
            handMotionEstimator.resetSession(evento.getSessionId());
            return "Evidencia bilateral de pose ausente, inválida o caducada";
        }
        long ingressNanos = evento.serverIngressAtMonotonicNanosOr(monotonicNanos.getAsLong());
        OpenCvHandMotionEstimator.Estimate estimate = handMotionEstimator.observe(
            evento.getSessionId(), evento.getProducerEpoch(), frameSequence,
            ingressNanos, pose);
        evento.setEvidenciaPoseManos(null);
        if (!estimate.valid()) return "OpenCV no pudo medir movimiento bilateral reciente";
        evento.setEvidenciaMovimiento(new MovementEvidence(frameSequence,
            estimate.visibleHands(), estimate.movementNormalized(), true,
            producerEvidence.antiguedadMs()));
        return null;
    }

    /** Queue a dashboard refresh for a state-only change, without re-running the Observer pipeline. */
    public void notificarCambioEstado(HandwashingSession sesion) {
        if (sesion == null) return;
        String sessionId = sesion.getSessionId();
        synchronized (sesion) {
            if (sesiones.get(sessionId) != sesion) return;
            receptor.notifyStateChanged(sessionId);
        }
    }

    @Scheduled(fixedDelayString = "${handwash.session.expiration-check-ms:1000}")
    public void expirarSesionesInactivas() {
        long timeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes);
        sesiones.values().forEach(sesion -> {
            boolean expired = false;
            synchronized (sesion) {
                long checkedAtEpochMs = System.currentTimeMillis();
                long checkedAtNanos = System.nanoTime();
                long inactiveForMs = sesion.getTiempoInactivoMs(checkedAtEpochMs, checkedAtNanos);
                if (sesion.estaInactivaDesde(checkedAtEpochMs, checkedAtNanos, timeoutMs)) {
                    metrics.recordExpirationDetectionDelay(Math.max(0L, inactiveForMs - timeoutMs));
                    sesion.expirar();
                    metrics.recordExpiration(HandwashMetrics.ExpirationCause.IDLE_TIMEOUT);
                    expired = true;
                }
            }
            if (expired) {
                // Expiration can archive an unfinished attempt in either
                // evaluator; queue its bounded metadata outside the camera path.
                failedAttemptPersistence.markPending(sesion);
                notificarCambioEstado(sesion);
            }
        });
    }

    @Scheduled(fixedDelayString = "${handwash.session.cleanup-check-ms:60000}")
    public void limpiarSesionesTerminadas() {
        long retentionMs = TimeUnit.MINUTES.toMillis(terminalRetentionMinutes);
        long ahora = System.currentTimeMillis();
        long ahoraNanos = System.nanoTime();
        List<String> expiradas = sesiones.entrySet().stream()
            .filter(entry -> {
                HandwashingSession sesion = entry.getValue();
                synchronized (sesion) {
                    boolean terminal = sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.COMPLETADA
                        || sesion.getEstadoSesion() == com.handwash.model.HandwashingSessionState.EXPIRADA;
                    return terminal
                        && sesion.getTiempoInactivoMs(ahora, ahoraNanos) >= retentionMs;
                }
            })
            .map(Map.Entry::getKey)
            .toList();
        // Centraliza el borrado de la sesión, su código y sus credenciales.
        // La sincronización evita eliminar el estado mientras se procesa un frame.
        expiradas.forEach(this::eliminarSesion);
        failedAttemptPersistence.deleteCreatedBefore(
            ahora - retentionMs, Set.copyOf(sesiones.keySet()));
    }

    @Scheduled(fixedDelayString = "${handwash.session.failed-attempt-persist-ms:500}")
    public void persistirIntentosFallidosPendientes() {
        failedAttemptPersistence.flushPending();
    }

    /** Flushes in-memory retry metadata on orderly shutdown without touching the camera path. */
    @PreDestroy
    void vaciarIntentosFallidosAlCerrar() {
        failedAttemptPersistence.flushSessions(sesiones.keySet());
    }

    /** A history read forces a flush outside the camera request path. */
    public boolean persistirIntentosFallidosPendientes(String sessionId) {
        return failedAttemptPersistence.flushSession(sessionId);
    }
}
