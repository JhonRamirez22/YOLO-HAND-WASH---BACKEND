package com.handwash.intention;

/** Server-owned bilateral-presence dwell; timestamps use only Java's monotonic clock. */
public final class HandPresenceWarmup {
    private final long requiredNanos;
    private final long maxGapNanos;
    private long lastFrameSequence = -1L;
    private long firstBilateralAtNanos;
    private long lastObservationAtNanos;
    private boolean hasObservation;
    private boolean ready;
    private int handsVisible;

    public HandPresenceWarmup(long requiredMs, long maxGapMs) {
        if (requiredMs < 0L || requiredMs > 10_000L) {
            throw new IllegalArgumentException("requiredMs debe estar entre 0 y 10000");
        }
        if (maxGapMs < 100L || maxGapMs > 1_000L) {
            throw new IllegalArgumentException("maxGapMs debe estar entre 100 y 1000");
        }
        requiredNanos = requiredMs * 1_000_000L;
        maxGapNanos = maxGapMs * 1_000_000L;
        ready = requiredMs == 0L;
    }

    /** Called under the owning session lock after the v2 envelope is authenticated. */
    public boolean observe(long frameSequence, int visibleHands, long receivedAtNanos,
                           boolean washStarted) {
        if (frameSequence < 0L || frameSequence <= lastFrameSequence
            || visibleHands < 0 || visibleHands > 2) {
            if (!washStarted) clearInterval();
            return false;
        }
        lastFrameSequence = frameSequence;
        handsVisible = visibleHands;
        if (visibleHands != 2) {
            if (!washStarted) {
                clearInterval();
                return false;
            }
            hasObservation = true;
            lastObservationAtNanos = receivedAtNanos;
            return false;
        }
        if (requiredNanos == 0L) {
            ready = true;
            hasObservation = true;
            lastObservationAtNanos = receivedAtNanos;
            return true;
        }

        long gap = receivedAtNanos - lastObservationAtNanos;
        if (!hasObservation || gap <= 0L || gap > maxGapNanos) {
            if (!washStarted || !ready) {
                firstBilateralAtNanos = receivedAtNanos;
                ready = false;
            }
        }
        lastObservationAtNanos = receivedAtNanos;
        hasObservation = true;
        if (!ready && receivedAtNanos - firstBilateralAtNanos >= requiredNanos) {
            ready = true;
        }
        return ready;
    }

    /** Fresh bilateral evidence remains mandatory for each step, even after readiness latches. */
    public boolean permitsStep(long nowNanos, boolean washStarted) {
        if (requiredNanos == 0L) return true;
        long age = nowNanos - lastObservationAtNanos;
        boolean freshBilateral = hasObservation && handsVisible == 2
            && age >= 0L && age <= maxGapNanos;
        if (!freshBilateral && !washStarted) clearInterval();
        return ready && freshBilateral;
    }

    /** A new producer epoch must prove presence again; frame numbering also restarts. */
    public void reset() {
        lastFrameSequence = -1L;
        clearInterval();
    }

    public long lastFrameSequence() { return lastFrameSequence; }
    public boolean isReady() { return ready; }

    private void clearInterval() {
        firstBilateralAtNanos = 0L;
        lastObservationAtNanos = 0L;
        hasObservation = false;
        handsVisible = 0;
        ready = requiredNanos == 0L;
    }
}
