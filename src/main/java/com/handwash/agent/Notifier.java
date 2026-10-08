package com.handwash.agent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import com.handwash.model.DetectionEvent;
import com.handwash.model.HandwashingStatusResponse;
import com.handwash.model.HandwashingSession;
import com.handwash.model.HandwashingSessionState;
import com.handwash.model.HandwashingStep;
import com.handwash.model.OmsAction;
import com.handwash.observer.DetectionObserver;
import com.handwash.service.HandwashMetrics;
import com.handwash.service.ClinicalDecisionPolicy;
import com.handwash.service.SessionManager;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import jakarta.websocket.SendResult;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Set;

@Component
public class Notifier implements DetectionObserver {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int OUTBOUND_WORKER_COUNT = 4;
    private static final int OUTBOUND_TASK_CAPACITY = 128;

    static boolean sendTimedOut(boolean inFlight, long startedAtNanos,
                                long nowNanos, long timeoutNanos) {
        return inFlight && nowNanos - startedAtNanos >= timeoutNanos;
    }

    private final SessionManager sessionManager;
    private final HandwashMetrics metrics;
    private final ClinicalDecisionPolicy clinicalDecisionPolicy;
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, ClientOutbound> outboundClients = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor outboundExecutor;
    /**
     * Cola de coalescencia: la UI solo necesita el estado más reciente de
     * cada sesión, no un WebSocket bloqueante por cada frame de cámara.
     */
    private final Set<String> estadosPendientes = ConcurrentHashMap.newKeySet();
    private final Set<String> resumenesCompletadosPendientes = ConcurrentHashMap.newKeySet();
    private final Set<String> resumenesExpiradosPendientes = ConcurrentHashMap.newKeySet();
    private final Set<String> expiredSummariesSent = ConcurrentHashMap.newKeySet();
    private final Set<String> completedSummariesSent = ConcurrentHashMap.newKeySet();
    @Value("${handwash.notification.send-timeout-ms:3000}")
    private long sendTimeoutMs = 3_000L;
    @Value("${handwash.notification.max-websocket-clients:256}")
    private int maxOutboundClients = 256;

    public Notifier(SessionManager sessionManager) {
        this(sessionManager, HandwashMetrics.noop(), new ClinicalDecisionPolicy());
    }

    public Notifier(SessionManager sessionManager, HandwashMetrics metrics) {
        this(sessionManager, metrics, new ClinicalDecisionPolicy());
    }

    @Autowired
    public Notifier(SessionManager sessionManager, HandwashMetrics metrics,
                       ClinicalDecisionPolicy clinicalDecisionPolicy) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.clinicalDecisionPolicy = clinicalDecisionPolicy;
        AtomicInteger number = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "handwash-websocket-send-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        outboundExecutor = new ThreadPoolExecutor(
            OUTBOUND_WORKER_COUNT, OUTBOUND_WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(OUTBOUND_TASK_CAPACITY), threads,
            new ThreadPoolExecutor.AbortPolicy());
        metrics.bindOutboundExecutor(outboundExecutor);
    }

    public synchronized boolean registrarSesion(String sessionId, WebSocketSession session) {
        return registrarSesionInterno(sessionId, session, null, null, null);
    }

    public synchronized boolean registrarSesion(String sessionId, WebSocketSession session,
                                                Long credentialExpiresAtEpochMs) {
        return registrarSesionInterno(sessionId, session, credentialExpiresAtEpochMs, null, null);
    }

    public synchronized boolean registrarSesion(String sessionId, WebSocketSession session,
                                                Long credentialExpiresAtEpochMs,
                                                Long credentialExpiresAtMonotonicNanos) {
        return registrarSesionInterno(sessionId, session, credentialExpiresAtEpochMs,
            credentialExpiresAtMonotonicNanos, null);
    }

    public synchronized boolean registrarSesion(String sessionId, WebSocketSession session,
                                                SessionManager.AccessInfo accessInfo) {
        Long wallExpiry = accessInfo == null ? null : accessInfo.expiresAtEpochMs();
        Long monotonicExpiry = accessInfo == null ? null : accessInfo.expiresAtMonotonicNanos();
        return registrarSesionInterno(sessionId, session, wallExpiry, monotonicExpiry, accessInfo);
    }

    private boolean registrarSesionInterno(String sessionId, WebSocketSession session,
                                           Long credentialExpiresAtEpochMs,
                                           Long credentialExpiresAtMonotonicNanos,
                                           SessionManager.AccessInfo accessInfo) {
        if (sessionId == null || session == null) return false;
        if (outboundClients.containsKey(session)) return true;
        if (outboundClients.size() >= Math.max(1, maxOutboundClients)) return false;
        sessions.compute(sessionId, (ignored, clientes) -> {
            Set<WebSocketSession> registrados = clientes == null
                ? ConcurrentHashMap.newKeySet() : clientes;
            registrados.add(session);
            return registrados;
        });
        outboundClients.computeIfAbsent(session,
            cliente -> new ClientOutbound(sessionId, cliente, credentialExpiresAtEpochMs,
                credentialExpiresAtMonotonicNanos, accessInfo));
        return true;
    }

    /** Sends the current state on connect/reconnect and a retained terminal summary if available. */
    public void enviarInstantanea(String sessionId, HandwashingSession sesion, WebSocketSession cliente) {
        try {
            if (!cliente.isOpen()) return;
            synchronized (sesion) {
                String estado = mapper.writeValueAsString(sesion.getEstadoActualResponse());
                String resumen = null;
                HandwashingSessionState estadoSesion = sesion.getEstadoSesion();
                if (estadoSesion == HandwashingSessionState.COMPLETADA || estadoSesion == HandwashingSessionState.EXPIRADA) {
                    resumen = mapper.writeValueAsString(crearResumen(sessionId, sesion));
                }
                ClientOutbound outbound = outboundClients.computeIfAbsent(
                    cliente, value -> new ClientOutbound(sessionId, value));
                long enqueueStarted = System.nanoTime();
                // Serialize and enqueue in one session-locked section. Otherwise
                // a newer event can enter the mailbox after this snapshot is read
                // but before it is queued, allowing stale state to overwrite it.
                if (resumen == null) outbound.enqueueState(estado);
                else outbound.enqueueFinal(estado, resumen);
                metrics.recordWebsocketMailboxEnqueue(System.nanoTime() - enqueueStarted);
            }
        } catch (JacksonException error) {
            log.warn("No se pudo serializar la instantánea del dashboard: {}",
                error.getClass().getSimpleName());
            eliminarSesion(sessionId, cliente);
        }
    }

    public void eliminarSesion(String sessionId) {
        Set<WebSocketSession> clientes = sessions.remove(sessionId);
        estadosPendientes.remove(sessionId);
        resumenesCompletadosPendientes.remove(sessionId);
        resumenesExpiradosPendientes.remove(sessionId);
        expiredSummariesSent.remove(sessionId);
        completedSummariesSent.remove(sessionId);
        if (clientes != null) {
            for (WebSocketSession cliente : clientes) {
                ClientOutbound outbound = outboundClients.remove(cliente);
                if (outbound != null) outbound.discard();
                try {
                    if (cliente.isOpen()) cliente.close(CloseStatus.NORMAL.withReason("Sesión eliminada"));
                } catch (IOException error) {
                    log.debug("No se pudo cerrar un WebSocket de sesión eliminada: {}",
                        error.getClass().getSimpleName());
                }
            }
        }
    }

    public void eliminarSesion(String sessionId, WebSocketSession session) {
        if (sessionId == null || session == null) return;
        ClientOutbound outbound = outboundClients.remove(session);
        if (outbound != null) outbound.discard();
        sessions.computeIfPresent(sessionId, (ignored, clientes) -> {
            clientes.remove(session);
            return clientes.isEmpty() ? null : clientes;
        });
    }

    @Override
    public void onDeteccion(DetectionEvent evento) {
        if (evento == null) return;
        String sessionId = evento.getSessionId();
        encolarEstadoActualizado(sessionId, sessionManager.getSesion(sessionId));
    }

    @Override
    public void onStateChanged(String sessionId) {
        encolarEstadoActualizado(sessionId, sessionManager.getSesion(sessionId));
    }

    private void encolarEstadoActualizado(String sessionId, HandwashingSession sesion) {
        long enqueueStarted = System.nanoTime();
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            if (sesion == null) return;
            Set<WebSocketSession> clientes = sessions.get(sessionId);
            if (clientes == null || clientes.isEmpty()) return;

            // Do not allocate a dashboard DTO on the camera request thread. The
            // scheduled flusher snapshots the latest session state after the
            // observer pipeline has completed and coalesces repeated frame events.
            estadosPendientes.add(sessionId);

            HandwashingSessionState estado = sesion.getEstadoSesion();
            if (estado == HandwashingSessionState.COMPLETADA) {
                // Observer corre dentro del lock de la sesión: difiere el envío
                // real para no bloquear la inferencia esperando al dashboard.
                resumenesCompletadosPendientes.add(sessionId);
            } else if (estado == HandwashingSessionState.EXPIRADA) {
                resumenesExpiradosPendientes.add(sessionId);
            }
        } finally {
            metrics.recordWebsocketPendingEnqueue(System.nanoTime() - enqueueStarted);
        }
    }

    @Scheduled(fixedRateString = "${handwash.notification.flush-ms:33}")
    public void vaciarEstadosPendientes() {
        estadosPendientes.forEach(this::enviarEstadoPendiente);
        resumenesCompletadosPendientes.forEach(id -> enviarResumenTerminalPendiente(id, HandwashingSessionState.COMPLETADA));
        resumenesExpiradosPendientes.forEach(id -> enviarResumenTerminalPendiente(id, HandwashingSessionState.EXPIRADA));
        dispatchPendingOutbound();
    }

    private void enviarResumenTerminalPendiente(String sessionId, HandwashingSessionState estadoEsperado) {
        HandwashingSession sesion = sessionManager.getSesion(sessionId);
        if (sesion == null) {
            limpiarTerminalPendiente(sessionId);
            return;
        }
        if (sesion.getEstadoSesion() != estadoEsperado) return;

        Set<String> enviados = estadoEsperado == HandwashingSessionState.COMPLETADA
            ? completedSummariesSent : expiredSummariesSent;
        if (enviados.add(sessionId) && !enviarResumen(sessionId, sesion)) {
            enviados.remove(sessionId);
            return;
        }
        limpiarTerminalPendiente(sessionId);
    }

    private void limpiarTerminalPendiente(String sessionId) {
        estadosPendientes.remove(sessionId);
        resumenesCompletadosPendientes.remove(sessionId);
        resumenesExpiradosPendientes.remove(sessionId);
    }

    private void enviarEstadoPendiente(String sessionId) {
        if (!estadosPendientes.contains(sessionId)) return;
        Set<WebSocketSession> clientes = sessions.get(sessionId);
        if (clientes == null || clientes.isEmpty()) {
            estadosPendientes.remove(sessionId);
            return;
        }
        HandwashingSession sesion = sessionManager.getSesion(sessionId);
        if (sesion == null) {
            estadosPendientes.remove(sessionId);
            return;
        }
        HandwashingSessionState estadoSesion = sesion.getEstadoSesion();
        if (estadoSesion == HandwashingSessionState.COMPLETADA || estadoSesion == HandwashingSessionState.EXPIRADA) {
            // Terminal sessions are flushed as a state+summary pair below.
            return;
        }
        // Claim the pending marker before taking the snapshot. If a newer update
        // arrives while serialization/enqueue is in progress, its add() then
        // remains set for the next flush instead of being erased here.
        if (!estadosPendientes.remove(sessionId)) return;
        enviarEstado(sessionId, sesion.getEstadoActualResponse());
    }

    @Scheduled(fixedDelayString = "${handwash.notification.expired-summary-recovery-ms:10000}")
    public void enviarResumenesExpirados() {
        sessionManager.getSesionesSnapshot().forEach((id, sesion) -> {
            if (sesion.getEstadoSesion() == HandwashingSessionState.EXPIRADA
                && !expiredSummariesSent.contains(id)) {
                resumenesExpiradosPendientes.add(id);
                enviarResumenTerminalPendiente(id, HandwashingSessionState.EXPIRADA);
            }
        });
        expiredSummariesSent.removeIf(id -> sessionManager.getSesion(id) == null);
        completedSummariesSent.removeIf(id -> sessionManager.getSesion(id) == null);
        resumenesCompletadosPendientes.removeIf(id -> sessionManager.getSesion(id) == null);
        resumenesExpiradosPendientes.removeIf(id -> sessionManager.getSesion(id) == null);
        sessions.keySet().stream()
            .filter(id -> sessionManager.getSesion(id) == null)
            .toList()
            .forEach(this::eliminarSesion);
        estadosPendientes.removeIf(id -> sessionManager.getSesion(id) == null);
        dispatchPendingOutbound();
    }

    public void enviarEstado(String sessionId, HandwashingStatusResponse response) {
        Set<WebSocketSession> clientes = sessions.get(sessionId);
        if (clientes == null || clientes.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(response);
        } catch (JacksonException e) {
            log.error("Error serializando estado: {}", e.getMessage());
            return;
        }
        for (WebSocketSession cliente : clientes) {
            long enqueueStarted = System.nanoTime();
            salida(sessionId, cliente).enqueueState(json);
            metrics.recordWebsocketMailboxEnqueue(System.nanoTime() - enqueueStarted);
        }
    }

    private boolean enviarResumen(String sessionId, HandwashingSession sesion) {
        Set<WebSocketSession> clientes = sessions.get(sessionId);
        if (clientes == null || clientes.isEmpty()) return true;

        try {
            String stateJson;
            String summaryJson;
            synchronized (sesion) {
                stateJson = mapper.writeValueAsString(sesion.getEstadoActualResponse());
                summaryJson = mapper.writeValueAsString(crearResumen(sessionId, sesion));
            }
            for (WebSocketSession cliente : clientes) {
                if (!cliente.isOpen()) {
                    eliminarSesion(sessionId, cliente);
                    continue;
                }
                long enqueueStarted = System.nanoTime();
                salida(sessionId, cliente).enqueueFinal(stateJson, summaryJson);
                metrics.recordWebsocketMailboxEnqueue(System.nanoTime() - enqueueStarted);
            }
            return true;
        } catch (JacksonException e) {
            log.error("Error serializando resumen: {}", e.getMessage());
            return false;
        }
    }

    /** One bounded, coalescing mailbox and one sender task per connected client. */
    private final class ClientOutbound {
        private final String sessionId;
        private final WebSocketSession session;
        private final Long credentialExpiresAtEpochMs;
        private final Long credentialExpiresAtMonotonicNanos;
        private final SessionManager.AccessInfo accessInfo;
        private String latestState;
        private String terminalSummary;
        private boolean terminalQueued;
        private boolean terminalSent;
        private boolean scheduled;
        private boolean closed;
        private volatile long sendingSinceNanos;
        private boolean sendInFlight;
        private Long terminalQueuedAtNanos;
        private final AtomicReference<Timer.Sample> activeSendTimer = new AtomicReference<>();

        private ClientOutbound(String sessionId, WebSocketSession session) {
            this(sessionId, session, null, null, null);
        }

        private ClientOutbound(String sessionId, WebSocketSession session,
                               Long credentialExpiresAtEpochMs) {
            this(sessionId, session, credentialExpiresAtEpochMs, null, null);
        }

        private ClientOutbound(String sessionId, WebSocketSession session,
                               Long credentialExpiresAtEpochMs,
                               Long credentialExpiresAtMonotonicNanos) {
            this(sessionId, session, credentialExpiresAtEpochMs,
                credentialExpiresAtMonotonicNanos, null);
        }

        private ClientOutbound(String sessionId, WebSocketSession session,
                               Long credentialExpiresAtEpochMs,
                               Long credentialExpiresAtMonotonicNanos,
                               SessionManager.AccessInfo accessInfo) {
            this.sessionId = sessionId;
            this.session = session;
            this.credentialExpiresAtEpochMs = credentialExpiresAtEpochMs;
            this.credentialExpiresAtMonotonicNanos = credentialExpiresAtMonotonicNanos;
            this.accessInfo = accessInfo;
        }

        private void enqueueState(String payload) {
            synchronized (this) {
                if (closed || terminalQueued) return;
                if (latestState != null) metrics.recordCoalescedState();
                latestState = payload;
            }
            scheduleIfPending();
        }

        /** Terminal output is atomic: the final state is always drained before its summary. */
        private void enqueueFinal(String statePayload, String summaryPayload) {
            synchronized (this) {
                if (closed || terminalQueued || terminalSent) return;
                latestState = statePayload;
                terminalSummary = summaryPayload;
                terminalQueued = true;
                terminalQueuedAtNanos = System.nanoTime();
            }
            scheduleIfPending();
        }

        private void scheduleIfPending() {
            synchronized (this) {
                if (closed || scheduled || (latestState == null && terminalSummary == null)) return;
                scheduled = true;
            }
            try {
                outboundExecutor.execute(this::drain);
            } catch (RejectedExecutionException saturated) {
                // The two-slot mailbox is retained. A later scheduled flush retries it.
                metrics.recordOutboundQueueRejected();
                synchronized (this) {
                    scheduled = false;
                }
            }
        }

        private void drain() {
            while (true) {
                String payload;
                boolean terminalSummaryPayload = false;
                synchronized (this) {
                    if (closed) {
                        scheduled = false;
                        return;
                    }
                    if (!session.isOpen()) {
                        closed = true;
                        latestState = null;
                        terminalSummary = null;
                        scheduled = false;
                        payload = null;
                    } else if (latestState != null) {
                        payload = latestState;
                        latestState = null;
                    } else if (terminalSummary != null) {
                        payload = terminalSummary;
                        terminalSummary = null;
                        terminalSummaryPayload = true;
                    } else {
                        scheduled = false;
                        return;
                    }
                }
                if (payload == null) {
                    eliminarSesion(sessionId, session);
                    return;
                }
                boolean finalSummary = terminalSummaryPayload;

                beginSend();
                try {
                    jakarta.websocket.Session nativeSession = session instanceof NativeWebSocketSession wrapper
                        ? wrapper.getNativeSession(jakarta.websocket.Session.class) : null;
                    if (nativeSession == null) {
                        // Keep compatibility with non-native WebSocketSession adapters
                        // (including test doubles); the production Tomcat adapter uses
                        // the asynchronous branch below.
                        synchronized (session) {
                            if (!session.isOpen()) {
                                finishSend();
                                eliminarSesion(sessionId, session);
                                return;
                            }
                            Timer.Sample sendSample = startSendTimer();
                            try {
                                session.sendMessage(new TextMessage(payload));
                            } catch (IOException | RuntimeException error) {
                                finishSendTimer(sendSample);
                                throw error;
                            }
                            finishSendTimer(sendSample);
                        }
                        onSendSuccess(finalSummary);
                        return;
                    }
                    if (!nativeSession.isOpen()) {
                        finishSend();
                        eliminarSesion(sessionId, session);
                        return;
                    }
                    nativeSession.getAsyncRemote().setSendTimeout(Math.max(1L, sendTimeoutMs));
                    Timer.Sample sendSample = startSendTimer();
                    try {
                        nativeSession.getAsyncRemote().sendText(payload,
                            result -> onSendComplete(result, sendSample, finalSummary));
                    } catch (RuntimeException error) {
                        finishSendTimer(sendSample);
                        throw error;
                    }
                    return;
                } catch (IOException | RuntimeException error) {
                    finishSend();
                    cerrarCliente(CloseStatus.SESSION_NOT_RELIABLE.withReason("Error de envío"),
                        "falló el envío", error, HandwashMetrics.SendFailureCause.SYNC_EXCEPTION);
                    return;
                }
            }
        }

        private void onSendComplete(SendResult result, Timer.Sample sendSample, boolean terminalSummaryPayload) {
            finishSend();
            finishSendTimer(sendSample);
            if (!result.isOK()) {
                cerrarCliente(CloseStatus.SESSION_NOT_RELIABLE.withReason("Falló el envío asíncrono"),
                    "falló el envío asíncrono", result.getException(),
                    HandwashMetrics.SendFailureCause.ASYNC_CALLBACK);
                return;
            }
            onSendSuccess(terminalSummaryPayload);
        }

        private void onSendSuccess(boolean terminalSummaryPayload) {
            finishSend();
            synchronized (this) {
                if (terminalSummaryPayload) {
                    terminalSent = true;
                    if (terminalQueuedAtNanos != null) {
                        metrics.recordTerminalDelivery(Math.max(
                            0L, System.nanoTime() - terminalQueuedAtNanos));
                        terminalQueuedAtNanos = null;
                    }
                }
                scheduled = false;
            }
            scheduleIfPending();
        }

        private synchronized void beginSend() {
            sendingSinceNanos = System.nanoTime();
            sendInFlight = true;
        }

        private synchronized void finishSend() {
            sendInFlight = false;
            sendingSinceNanos = 0L;
        }

        private boolean isCredentialExpired(long nowEpochMs, long nowMonotonicNanos) {
            boolean wallClockExpired = credentialExpiresAtEpochMs != null
                && nowEpochMs >= credentialExpiresAtEpochMs;
            boolean monotonicExpired = credentialExpiresAtMonotonicNanos != null
                && nowMonotonicNanos - credentialExpiresAtMonotonicNanos >= 0L;
            return wallClockExpired || monotonicExpired;
        }

        private boolean isCredentialRevoked() {
            return accessInfo != null && !sessionManager.credencialVigente(sessionId, accessInfo);
        }

        private void closeForExpiredCredential() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                latestState = null;
                terminalSummary = null;
                sendInFlight = false;
                sendingSinceNanos = 0L;
            }
            Timer.Sample abandonedSend = activeSendTimer.getAndSet(null);
            metrics.stopWebsocketSend(abandonedSend);
            eliminarSesion(sessionId, session);
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Credencial expirada"));
                }
            } catch (IOException error) {
                log.debug("No se pudo cerrar un WebSocket con credencial expirada: {}",
                    error.getClass().getSimpleName());
            }
        }

        private void closeForRevokedCredential() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                latestState = null;
                terminalSummary = null;
                sendInFlight = false;
                sendingSinceNanos = 0L;
            }
            Timer.Sample abandonedSend = activeSendTimer.getAndSet(null);
            metrics.stopWebsocketSend(abandonedSend);
            eliminarSesion(sessionId, session);
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Credencial revocada"));
                }
            } catch (IOException error) {
                log.debug("No se pudo cerrar un WebSocket con credencial revocada: {}",
                    error.getClass().getSimpleName());
            }
        }

        private boolean closeIfSendingTooLong(long nowNanos, long timeoutNanos) {
            synchronized (this) {
                if (closed || !sendTimedOut(sendInFlight, sendingSinceNanos,
                    nowNanos, timeoutNanos)) return false;
                closed = true;
                latestState = null;
                terminalSummary = null;
                sendInFlight = false;
                sendingSinceNanos = 0L;
            }
            cerrarClienteCerrado(CloseStatus.SESSION_NOT_RELIABLE.withReason("Cliente lento"),
                "excedió el tiempo máximo de envío", null,
                HandwashMetrics.SendFailureCause.TIMEOUT);
            return true;
        }

        private void cerrarCliente(CloseStatus status, String reason, Throwable cause,
                                   HandwashMetrics.SendFailureCause failureCause) {
            synchronized (this) {
                if (closed) return;
                closed = true;
                latestState = null;
                terminalSummary = null;
                sendInFlight = false;
                sendingSinceNanos = 0L;
            }
            cerrarClienteCerrado(status, reason, cause, failureCause);
        }

        /** Performs external cleanup after this client has been atomically claimed for closure. */
        private void cerrarClienteCerrado(CloseStatus status, String reason, Throwable cause,
                                           HandwashMetrics.SendFailureCause failureCause) {
            Timer.Sample abandonedSend = activeSendTimer.getAndSet(null);
            metrics.stopWebsocketSend(abandonedSend);
            metrics.recordSendFailure(failureCause);
            log.warn("Se desconecta un dashboard ({}, {})", reason,
                cause == null ? "sin detalle" : cause.getClass().getSimpleName());
            eliminarSesion(sessionId, session);
            try {
                if (session.isOpen()) {
                    session.close(status);
                }
            } catch (IOException error) {
                log.debug("No se pudo cerrar un WebSocket de dashboard: {}",
                    error.getClass().getSimpleName());
            }
        }

        private void discard() {
            synchronized (this) {
                closed = true;
                latestState = null;
                terminalSummary = null;
                sendInFlight = false;
                sendingSinceNanos = 0L;
            }
            // A disconnected transport is not guaranteed to invoke its async
            // callback. Stop its timer here; a late callback cannot double-stop
            // it because finishSendTimer uses compareAndSet.
            metrics.stopWebsocketSend(activeSendTimer.getAndSet(null));
        }

        private Timer.Sample startSendTimer() {
            Timer.Sample sample = metrics.startWebsocketSend();
            if (sample != null) activeSendTimer.set(sample);
            return sample;
        }

        private void finishSendTimer(Timer.Sample sample) {
            if (sample != null && activeSendTimer.compareAndSet(sample, null)) {
                metrics.stopWebsocketSend(sample);
            }
        }
    }

    private ClientOutbound salida(String sessionId, WebSocketSession cliente) {
        return outboundClients.computeIfAbsent(cliente,
            value -> new ClientOutbound(sessionId, value));
    }

    /** Retry rejected dispatches and close clients whose in-flight send exceeded its deadline. */
    private void dispatchPendingOutbound() {
        long now = System.nanoTime();
        long nowEpochMs = System.currentTimeMillis();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, sendTimeoutMs));
        outboundClients.values().forEach(client -> {
            if (client.isCredentialExpired(nowEpochMs, now)) {
                client.closeForExpiredCredential();
            } else if (client.isCredentialRevoked()) {
                client.closeForRevokedCredential();
            } else {
                if (!client.closeIfSendingTooLong(now, timeoutNanos)) {
                    client.scheduleIfPending();
                }
            }
        });
    }

    @PreDestroy
    void cerrarEnviadores() {
        outboundClients.values().forEach(ClientOutbound::discard);
        outboundClients.clear();
        outboundExecutor.shutdownNow();
    }

    Map<String, Object> crearResumen(String sessionId, HandwashingSession sesion) {
        Map<String, Object> summary = new LinkedHashMap<>();
        synchronized (sesion) {
            HandwashingSessionState estado = sesion.getEstadoSesion();
            boolean completa = estado == HandwashingSessionState.COMPLETADA;
            boolean intentoFinalLimpio = sesion.getInfraccionesIntentoActual().isEmpty();
            boolean modoOms = "PROTOCOLO_OMS".equals(sesion.getModoEvaluacionSeleccionado());
            boolean procedimientoValidado = sesion.isProcedimientoCompletoValidado();
            boolean clinicalDecisionAllowed = clinicalDecisionPolicy.isClinicalDecisionAllowed();
            String alcance = modoOms
                ? "SECUENCIA_OMS_CON_EVIDENCIA_VISUAL; NO_CERTIFICA_ESTERILIDAD_NI_AUSENCIA_DE_MICROORGANISMOS"
                : sesion.getEstrategia().getAlcanceEvaluacion();
            summary.put("messageType", "SESSION_SUMMARY");
            summary.put("sessionId", sessionId);
            summary.put("estadoSesion", estado.name());
            summary.put("modoEvaluacion", sesion.getModoEvaluacion());
            summary.put("metodoObjetivo", sesion.getEstrategia().getMetodoObjetivo());
            summary.put("alcanceEvaluacion", alcance);
            summary.put("procedimientoCompletoValidado", procedimientoValidado);
            summary.put("clinicalDecisionAllowed", clinicalDecisionAllowed);
            summary.put("duracionMinimaObjetivoMs", modoOms
                ? sesion.getSesionOms().getMinimumTotalMs() : sesion.getEstrategia().getDuracionTotalMs());
            summary.put("accionesNoDetectadas", sesion.getAccionesNoDetectadas());
            summary.put("resultado", !completa ? "INCOMPLETO"
                : !procedimientoValidado ? (modoOms ? "SECUENCIA_OMS_COMPLETADA_SIN_VALIDAR_MODELO_Y_EVIDENCIA"
                    : "SECUENCIA_COMPLETADA_SIN_VALIDAR_PROCEDIMIENTO_COMPLETO")
                : !clinicalDecisionAllowed ? (modoOms
                    ? "SECUENCIA_OMS_COMPLETADA_SIN_AUTORIZACION_CLINICA"
                    : "SECUENCIA_COMPLETADA_SIN_AUTORIZACION_CLINICA")
                : intentoFinalLimpio ? "APROBADO" : "SECUENCIA_COMPLETADA_CON_INFRACCIONES");
            long tiempoTotalMs = sesion.getTiempoTotalActivoMs();
            summary.put("tiempoTotalSegundos", tiempoTotalMs / 1000);
            summary.put("aprobado", completa && intentoFinalLimpio
                && procedimientoValidado && clinicalDecisionAllowed);
            summary.put("infracciones", sesion.getHistorialInfracciones());
            summary.put("infraccionesIntentoFinal", sesion.getInfraccionesIntentoActual());
            summary.put("intentosReiniciados", sesion.getIntentosReiniciados());
            summary.put("intentoFinal", sesion.getNumeroIntentoActual());
            summary.put("intentoFinalSinInfracciones", sesion.getInfraccionesIntentoActual().isEmpty());
            summary.put("historialIntentos", sesion.getIntentosAnteriores());
            summary.put("infraccionesOmitidas", sesion.getInfraccionesOmitidas());
            summary.put("coberturaJabon", sesion.getCoberturaJabon());
            summary.put("coberturaJabonCompleta", sesion.isCoberturaJabonCompleta());

            Map<String, Long> tiempos = new LinkedHashMap<>();
            Map<String, Long> tiemposActuales = new LinkedHashMap<>();
            if (modoOms) {
                tiemposActuales.putAll(sesion.getTiempoPorAccionOmsMs());
            } else {
                sesion.getTiempoPorPasoMs().forEach(
                    (paso, tiempoMs) -> tiemposActuales.put(paso.name(), tiempoMs));
            }
            tiemposActuales.forEach((paso, tiempoMs) -> tiempos.put(paso, tiempoMs / 1000));
            summary.put("tiempoPorPaso", tiempos);
            java.util.List<String> faltantes = modoOms
                ? OmsAction.SECUENCIA.stream().filter(accion -> accion.getOrden() > sesion.getPasosCompletados())
                    .map(OmsAction::getClaseModelo).toList()
                : java.util.Arrays.stream(HandwashingStep.values())
                    .filter(paso -> paso != HandwashingStep.FONDO && paso.getNumero() > sesion.getPasosCompletados())
                    .map(Enum::name).toList();
            summary.put("pasosFaltantes", faltantes);
            summary.put("confianzaPromedio", sesion.getConfianzaPromedio());
        }

        return summary;
    }
}
