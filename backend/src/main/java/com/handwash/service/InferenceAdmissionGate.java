package com.handwash.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds concurrent local inference so uploads are rejected instead of queuing stale work. */
@Component
public final class InferenceAdmissionGate {
    private final Semaphore permits;

    public InferenceAdmissionGate(
        @Value("${handwash.inference.max-concurrent:1}") int maxConcurrent
    ) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("handwash.inference.max-concurrent must be at least 1");
        }
        permits = new Semaphore(maxConcurrent);
    }

    /** Returns immediately with a one-shot permit, or {@code null} when saturated. */
    public Permit tryAcquire() {
        return permits.tryAcquire() ? new Permit(permits) : null;
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) semaphore.release();
        }
    }
}
