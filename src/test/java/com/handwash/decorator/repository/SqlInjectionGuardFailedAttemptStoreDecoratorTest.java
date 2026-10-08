package com.handwash.decorator.repository;

import com.handwash.repository.FailedAttemptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlInjectionGuardFailedAttemptStoreDecoratorTest {
    @Test
    void rejectsInjectedSessionIdentifierBeforeDelegating() {
        AtomicInteger calls = new AtomicInteger();
        FailedAttemptStore target = targetCountingCalls(calls);
        var decorator = new SqlInjectionGuardFailedAttemptStoreDecorator(target);

        assertThrows(IllegalArgumentException.class,
            () -> decorator.findBySession("x' OR '1'='1"));
        assertEquals(0, calls.get());
    }

    @Test
    void permitsOnlyCanonicalApplicationSessionIds() {
        AtomicInteger calls = new AtomicInteger();
        FailedAttemptStore target = targetCountingCalls(calls);
        var decorator = new SqlInjectionGuardFailedAttemptStoreDecorator(target);
        String id = UUID.randomUUID().toString();

        assertTrue(decorator.findBySession(id).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInjectedIdentifierInRetentionExclusionSetBeforeDelegating() {
        AtomicInteger calls = new AtomicInteger();
        var decorator = new SqlInjectionGuardFailedAttemptStoreDecorator(targetCountingCalls(calls));

        assertThrows(IllegalArgumentException.class,
            () -> decorator.deleteCreatedBefore(0L, Set.of("x' OR '1'='1")));
        assertEquals(0, calls.get());
    }

    private FailedAttemptStore targetCountingCalls(AtomicInteger calls) {
        return new FailedAttemptStore() {
            @Override public void insertIfAbsent(String sessionId,
                                                 com.handwash.model.HandwashingAttemptSummary attempt) {
                calls.incrementAndGet();
            }
            @Override public List<com.handwash.model.HandwashingAttemptSummary> findBySession(String sessionId) {
                calls.incrementAndGet();
                return List.of();
            }
            @Override public int deleteBySession(String sessionId) {
                calls.incrementAndGet();
                return 0;
            }
            @Override public int deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds) {
                calls.incrementAndGet();
                return 0;
            }
        };
    }
}
