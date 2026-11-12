package com.handwash.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRateLimiterTest {
    @Test
    void rejectsInvalidOrUnboundedRuntimeConfiguration() {
        RequestRateLimiter limiter = new RequestRateLimiter();

        ReflectionTestUtils.setField(limiter, "windowMs", 0L);
        assertThrows(IllegalStateException.class, limiter::validateConfiguration);
        ReflectionTestUtils.setField(limiter, "windowMs", TimeUnit.DAYS.toMillis(1) + 1L);
        assertThrows(IllegalStateException.class, limiter::validateConfiguration);

        ReflectionTestUtils.setField(limiter, "windowMs", 60_000L);
        ReflectionTestUtils.setField(limiter, "createLimit", 0);
        assertThrows(IllegalStateException.class, limiter::validateConfiguration);
        ReflectionTestUtils.setField(limiter, "createLimit", 20);
        ReflectionTestUtils.setField(limiter, "pairLimit", 10_001);
        assertThrows(IllegalStateException.class, limiter::validateConfiguration);
        ReflectionTestUtils.setField(limiter, "pairLimit", 10);
        ReflectionTestUtils.setField(limiter, "maxClients", 100_001);
        assertThrows(IllegalStateException.class, limiter::validateConfiguration);

        ReflectionTestUtils.setField(limiter, "maxClients", 10_000);
        limiter.validateConfiguration();
    }

    @Test
    void appliesIndependentClientAndOperationLimitsAndExpiresTheSlidingWindow() {
        AtomicLong nowNanos = new AtomicLong(10_000_000_000L);
        RequestRateLimiter limiter = new RequestRateLimiter(nowNanos::get);
        ReflectionTestUtils.setField(limiter, "windowMs", 1_000L);
        ReflectionTestUtils.setField(limiter, "createLimit", 2);
        ReflectionTestUtils.setField(limiter, "pairLimit", 1);

        assertTrue(limiter.allowSessionCreation("client-a"));
        assertTrue(limiter.allowSessionCreation("client-a"));
        assertFalse(limiter.allowSessionCreation("client-a"));
        assertTrue(limiter.allowSessionCreation("client-b"), "clients have separate buckets");
        assertTrue(limiter.allowPairing("client-a"), "pairing has an independent operation bucket");
        assertFalse(limiter.allowPairing("client-a"));

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_001L));

        assertTrue(limiter.allowSessionCreation("client-a"), "expired requests leave the sliding window");
        assertTrue(limiter.allowPairing("client-a"));
    }

    @Test
    void slidingWindowRemainsCorrectWhenMonotonicCounterWraps() {
        AtomicLong nowNanos = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        RequestRateLimiter limiter = new RequestRateLimiter(nowNanos::get);
        ReflectionTestUtils.setField(limiter, "windowMs", 1_000L);
        ReflectionTestUtils.setField(limiter, "pairLimit", 1);

        assertTrue(limiter.allowPairing("camera"));
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(999L)); // signed long wraps here
        assertFalse(limiter.allowPairing("camera"), "the request is still inside the window");
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2L));
        assertTrue(limiter.allowPairing("camera"), "the request expires at the elapsed-time boundary");
    }
}
