package com.handwash.service.persistence;

import com.handwash.model.HandwashingAttemptSummary;
import com.handwash.model.HandwashingSession;
import com.handwash.model.ProtocolType;
import com.handwash.repository.FailedAttemptStore;
import com.handwash.service.HandwashMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailedAttemptPersistenceCoordinatorTest {
    @Test
    void recordsPersistenceFailuresWithOnlyFixedOperationTags() {
        String sessionId = "74a5ca56-ae8b-4f4e-a790-0f95ad92008e";
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandwashMetrics metrics = new HandwashMetrics(registry);
        FakeSession session = new FakeSession(sessionId, List.of(sampleAttempt()));
        FailedAttemptStore store = new FailedAttemptStore() {
            @Override
            public void insertIfAbsent(String ignored, HandwashingAttemptSummary attempt) {
                throw new DataAccessResourceFailureException("private database detail");
            }

            @Override public List<HandwashingAttemptSummary> findBySession(String ignored) { return List.of(); }

            @Override
            public int deleteBySession(String ignored) {
                throw new DataAccessResourceFailureException("private database detail");
            }

            @Override
            public int deleteCreatedBefore(long ignored, Set<String> retained) {
                throw new DataAccessResourceFailureException("private database detail");
            }
        };
        FailedAttemptPersistenceCoordinator coordinator = new FailedAttemptPersistenceCoordinator(
            store, ignored -> session, System::nanoTime, metrics);

        coordinator.markPending(session);
        assertEquals(false, coordinator.flushSession(sessionId));
        assertEquals(false, coordinator.deleteSession(sessionId, () -> {}));
        coordinator.deleteCreatedBefore(0L, Set.of(sessionId));

        assertEquals(1.0, registry.get("handwash.failed-attempt.persistence.failures")
            .tag("operation", "write").counter().count());
        assertEquals(1.0, registry.get("handwash.failed-attempt.persistence.failures")
            .tag("operation", "delete").counter().count());
        assertEquals(1.0, registry.get("handwash.failed-attempt.persistence.failures")
            .tag("operation", "retention_cleanup").counter().count());
        assertEquals(3, registry.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("handwash.failed-attempt.persistence.failures"))
            .count());
    }

    @Test
    void retriesTransientStorageFailureAfterMonotonicDelayAcrossCounterWrap() {
        String sessionId = "74a5ca56-ae8b-4f4e-a790-0f95ad92008e";
        FakeSession session = new FakeSession(sessionId, List.of(sampleAttempt()));
        AtomicLong nowNanos = new AtomicLong(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(2));
        AtomicInteger writes = new AtomicInteger();
        FailedAttemptStore store = new FailedAttemptStore() {
            @Override
            public void insertIfAbsent(String ignored, HandwashingAttemptSummary attempt) {
                if (writes.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("temporary database failure");
                }
            }

            @Override public List<HandwashingAttemptSummary> findBySession(String ignored) { return List.of(); }
            @Override public int deleteBySession(String ignored) { return 0; }
            @Override public int deleteCreatedBefore(long ignored, Set<String> retained) { return 0; }
        };
        FailedAttemptPersistenceCoordinator coordinator = new FailedAttemptPersistenceCoordinator(
            store, ignored -> session, nowNanos::get);

        coordinator.markPending(session);
        assertEquals(false, coordinator.flushSession(sessionId),
            "a forced read must report that the cache could not be confirmed");
        assertEquals(1, writes.get());

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(4_999));
        coordinator.flushPending();
        assertEquals(1, writes.get(), "retry must respect the five-second delay after wrap");

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
        coordinator.flushPending();
        coordinator.flushPending();
        assertEquals(2, writes.get(), "a successful retry persists once and clears pending work");
        assertEquals(true, coordinator.flushSession(sessionId));
    }

    @Test
    void failedSessionDeletionKeepsSessionAndCacheForAnExplicitRetry() {
        String sessionId = "74a5ca56-ae8b-4f4e-a790-0f95ad92008e";
        AtomicInteger deletes = new AtomicInteger();
        AtomicBoolean persistedRow = new AtomicBoolean(true);
        AtomicBoolean removedSession = new AtomicBoolean();
        FailedAttemptStore store = new FailedAttemptStore() {
            @Override public void insertIfAbsent(String ignored, HandwashingAttemptSummary attempt) {}
            @Override public List<HandwashingAttemptSummary> findBySession(String ignored) { return List.of(); }
            @Override public int deleteBySession(String ignored) {
                if (deletes.incrementAndGet() == 1) {
                    throw new DataAccessResourceFailureException("temporary database failure");
                }
                persistedRow.set(false);
                return 1;
            }
            @Override public int deleteCreatedBefore(long ignored, Set<String> retained) { return 0; }
        };
        FailedAttemptPersistenceCoordinator coordinator = new FailedAttemptPersistenceCoordinator(
            store, ignored -> null);

        assertEquals(false, coordinator.deleteSession(sessionId, () -> removedSession.set(true)));
        assertEquals(true, persistedRow.get(), "failed database deletion must retain the cache row");
        assertEquals(false, removedSession.get(), "do not remove session state before cache deletion succeeds");

        assertEquals(true, coordinator.deleteSession(sessionId, () -> removedSession.set(true)));
        assertEquals(false, persistedRow.get());
        assertEquals(true, removedSession.get());
    }

    @Test
    void serializesAnInFlightWriteBeforeDeletingThatSessionsCache() throws Exception {
        String sessionId = "74a5ca56-ae8b-4f4e-a790-0f95ad92008e";
        FakeSession session = new FakeSession(sessionId, List.of(sampleAttempt()));
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicBoolean rowExists = new AtomicBoolean(false);
        AtomicBoolean sessionRemoved = new AtomicBoolean(false);
        FailedAttemptStore store = new FailedAttemptStore() {
            @Override
            public void insertIfAbsent(String ignored, HandwashingAttemptSummary attempt) {
                rowExists.set(true);
                writeEntered.countDown();
                try {
                    if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release test write");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test write interrupted", interrupted);
                }
            }

            @Override public List<HandwashingAttemptSummary> findBySession(String ignored) { return List.of(); }
            @Override public int deleteBySession(String ignored) {
                rowExists.set(false);
                return 1;
            }
            @Override public int deleteCreatedBefore(long ignored, Set<String> retained) { return 0; }
        };
        FailedAttemptPersistenceCoordinator coordinator = new FailedAttemptPersistenceCoordinator(
            store, ignored -> session);
        coordinator.markPending(session);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> write = executor.submit(() -> coordinator.flushSession(sessionId));
            org.junit.jupiter.api.Assertions.assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
            Future<Boolean> delete = executor.submit(() -> {
                deleteStarted.countDown();
                return coordinator.deleteSession(sessionId, () -> sessionRemoved.set(true));
            });
            org.junit.jupiter.api.Assertions.assertTrue(deleteStarted.await(2, TimeUnit.SECONDS));
            releaseWrite.countDown();

            assertEquals(true, write.get(2, TimeUnit.SECONDS));
            assertEquals(true, delete.get(2, TimeUnit.SECONDS));
        } finally {
            releaseWrite.countDown();
            executor.shutdownNow();
        }

        assertEquals(false, rowExists.get(), "a queued write must not recreate the cache after deletion");
        assertEquals(true, sessionRemoved.get());
    }

    private HandwashingAttemptSummary sampleAttempt() {
        return new HandwashingAttemptSummary(1, "REINICIADO", "PASO_FUERA_DE_SECUENCIA",
            1_000L, Map.of(), List.of());
    }

    private static final class FakeSession extends HandwashingSession {
        private final List<HandwashingAttemptSummary> attempts;

        private FakeSession(String sessionId, List<HandwashingAttemptSummary> attempts) {
            super(sessionId, ProtocolType.DOMESTICO);
            this.attempts = new ArrayList<>(attempts);
        }

        @Override
        public synchronized List<HandwashingAttemptSummary> getIntentosAnteriores() {
            return List.copyOf(attempts);
        }

        @Override
        public synchronized int getIntentosReiniciados() {
            return attempts.size();
        }
    }
}
