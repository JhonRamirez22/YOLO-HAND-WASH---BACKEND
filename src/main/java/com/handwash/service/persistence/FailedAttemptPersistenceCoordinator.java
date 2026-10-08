package com.handwash.service.persistence;

import com.handwash.model.IntentoLavadoResumen;
import com.handwash.model.SesionLavado;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.service.HandwashMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Owns asynchronous persistence, retry state and per-session serialization for
 * failed-attempt summaries. It never stores frames, detections or active state.
 */
public final class FailedAttemptPersistenceCoordinator {
    private static final Logger log = LoggerFactory.getLogger(FailedAttemptPersistenceCoordinator.class);

    private static final class PersistenceLock {
        private final Object monitor = new Object();
        /** Accessed only inside ConcurrentHashMap.compute for this session ID. */
        private int users;
    }

    private final FailedAttemptStore store;
    private final Function<String, SesionLavado> sessionLookup;
    private final LongSupplier monotonicNanos;
    private final HandwashMetrics metrics;
    private final Map<String, Integer> persistedThroughBySession = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfterMonotonicNanosBySession = new ConcurrentHashMap<>();
    private final Set<String> pendingSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, PersistenceLock> persistenceLocksBySession = new ConcurrentHashMap<>();

    public FailedAttemptPersistenceCoordinator(
        FailedAttemptStore store,
        Function<String, SesionLavado> sessionLookup
    ) {
        this(store, sessionLookup, System::nanoTime, HandwashMetrics.noop());
    }

    public FailedAttemptPersistenceCoordinator(
        FailedAttemptStore store,
        Function<String, SesionLavado> sessionLookup,
        HandwashMetrics metrics
    ) {
        this(store, sessionLookup, System::nanoTime, metrics);
    }

    FailedAttemptPersistenceCoordinator(
        FailedAttemptStore store,
        Function<String, SesionLavado> sessionLookup,
        LongSupplier monotonicNanos
    ) {
        this(store, sessionLookup, monotonicNanos, HandwashMetrics.noop());
    }

    FailedAttemptPersistenceCoordinator(
        FailedAttemptStore store,
        Function<String, SesionLavado> sessionLookup,
        LongSupplier monotonicNanos,
        HandwashMetrics metrics
    ) {
        this.store = store;
        this.sessionLookup = sessionLookup;
        this.monotonicNanos = monotonicNanos;
        this.metrics = metrics;
    }

    /** Called while the session pipeline owns the session monitor; does no I/O. */
    public void markPending(SesionLavado session) {
        if (store == null || session == null) return;
        synchronized (session) {
            String sessionId = session.getSessionId();
            int persistedThrough = persistedThroughBySession.getOrDefault(sessionId, 0);
            if (session.getIntentosReiniciados() > persistedThrough) {
                pendingSessions.add(sessionId);
            }
        }
    }

    /** Periodic bounded work; failed writes remain pending and are retried later. */
    public void flushPending() {
        if (store == null) return;
        List.copyOf(pendingSessions).forEach(sessionId -> persist(sessionId, false));
    }

    /** A history read forces a flush so the response includes the latest failures. */
    public boolean flushSession(String sessionId) {
        return persist(sessionId, true);
    }

    /** Flushes only currently registered sessions during orderly shutdown. */
    public void flushSessions(Collection<String> sessionIds) {
        if (store == null || sessionIds == null) return;
        List.copyOf(sessionIds).forEach(sessionId -> persist(sessionId, true));
    }

    /**
     * Serializes session removal, row deletion and coordinator cleanup against
     * an in-flight persistence attempt without holding the session monitor over
     * database I/O.
     */
    public boolean deleteSession(String sessionId, Runnable removeSessionState) {
        if (sessionId == null) return true;
        return withPersistenceLock(sessionId, () -> {
            // Do not report deletion or revoke the live session until the local
            // failure cache confirms removal. A failed DELETE remains retryable
            // by the caller instead of becoming an untracked privacy residue.
            if (!deletePersistedAttempts(sessionId)) return false;
            if (removeSessionState != null) removeSessionState.run();
            persistedThroughBySession.remove(sessionId);
            retryAfterMonotonicNanosBySession.remove(sessionId);
            pendingSessions.remove(sessionId);
            return true;
        });
    }

    /** Retention cleanup is deliberately independent of the camera/session lock. */
    public void deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds) {
        if (store == null) return;
        try {
            store.deleteCreatedBefore(cutoffEpochMs,
                Set.copyOf(Objects.requireNonNull(retainedSessionIds,
                    "retainedSessionIds es obligatorio para limpiar la caché")));
        } catch (DataAccessException error) {
            metrics.recordFailedAttemptPersistenceFailure(
                HandwashMetrics.PersistenceFailureCause.RETENTION_CLEANUP);
            log.warn("No se pudo limpiar la caché persistida de intentos fallidos: {}",
                error.getClass().getSimpleName());
        }
    }

    private boolean persist(String sessionId, boolean force) {
        if (store == null || sessionId == null) return false;
        if (!force && !retryDelayElapsed(sessionId, monotonicNanos.getAsLong())) return false;

        return withPersistenceLock(sessionId, () -> {
            if (!force && !retryDelayElapsed(sessionId, monotonicNanos.getAsLong())) return false;

            SesionLavado session = sessionLookup.apply(sessionId);
            if (session == null) {
                pendingSessions.remove(sessionId);
                return false;
            }

            int persistedThrough = persistedThroughBySession.getOrDefault(sessionId, 0);
            List<IntentoLavadoResumen> attempts;
            synchronized (session) {
                if (sessionLookup.apply(sessionId) != session) {
                    pendingSessions.remove(sessionId);
                    return false;
                }
                attempts = session.getIntentosAnteriores();
            }

            try {
                for (IntentoLavadoResumen attempt : attempts) {
                    if (attempt.numero() <= persistedThrough) continue;
                    store.insertIfAbsent(sessionId, attempt);
                    persistedThrough = Math.max(persistedThrough, attempt.numero());
                    persistedThroughBySession.put(sessionId, persistedThrough);
                }
                retryAfterMonotonicNanosBySession.remove(sessionId);

                // Re-check under the session monitor to close the race with a
                // newly failed attempt while database I/O was in progress.
                boolean complete;
                synchronized (session) {
                    if (sessionLookup.apply(sessionId) != session) {
                        pendingSessions.remove(sessionId);
                        complete = false;
                    } else if (session.getIntentosReiniciados() > persistedThrough) {
                        pendingSessions.add(sessionId);
                        complete = false;
                    } else {
                        pendingSessions.remove(sessionId);
                        complete = true;
                    }
                }
                return complete;
            } catch (DataAccessException | IllegalStateException error) {
                metrics.recordFailedAttemptPersistenceFailure(
                    HandwashMetrics.PersistenceFailureCause.WRITE);
                retryAfterMonotonicNanosBySession.put(sessionId,
                    monotonicNanos.getAsLong() + TimeUnit.SECONDS.toNanos(5L));
                pendingSessions.add(sessionId);
                log.warn("No se pudo guardar un resumen de intento fallido; se reintentará ({})",
                    error.getClass().getSimpleName());
                return false;
            }
        });
    }

    /**
     * Acquires a per-session monitor without leaking locks or allowing a waiter
     * to race with lock removal and create a second monitor for the same key.
     * The reference count is changed atomically with map membership; it includes
     * both the current owner and every thread already waiting on that monitor.
     */
    private <T> T withPersistenceLock(String sessionId, Supplier<T> operation) {
        PersistenceLock lock = persistenceLocksBySession.compute(sessionId, (key, current) -> {
            PersistenceLock selected = current == null ? new PersistenceLock() : current;
            selected.users++;
            return selected;
        });
        try {
            synchronized (lock.monitor) {
                return operation.get();
            }
        } finally {
            persistenceLocksBySession.compute(sessionId, (key, current) -> {
                if (current != lock || current.users <= 0) {
                    throw new IllegalStateException("Estado de lock de persistencia inconsistente");
                }
                current.users--;
                return current.users == 0 ? null : current;
            });
        }
    }

    private boolean retryDelayElapsed(String sessionId, long nowNanos) {
        Long retryAfter = retryAfterMonotonicNanosBySession.get(sessionId);
        // Monotonic subtraction remains valid across signed-long wrap for this
        // short deadline; wall-clock corrections cannot strand pending writes.
        return retryAfter == null || nowNanos - retryAfter >= 0L;
    }

    private boolean deletePersistedAttempts(String sessionId) {
        if (store == null) return true;
        try {
            store.deleteBySession(sessionId);
            return true;
        } catch (DataAccessException error) {
            // The session remains available so the API can report failure and
            // the owner can retry; retention cleanup is only a later fallback.
            metrics.recordFailedAttemptPersistenceFailure(
                HandwashMetrics.PersistenceFailureCause.DELETE);
            log.warn("No se pudo borrar una caché de intento fallido; la sesión sigue disponible ({})",
                error.getClass().getSimpleName());
            return false;
        }
    }
}
