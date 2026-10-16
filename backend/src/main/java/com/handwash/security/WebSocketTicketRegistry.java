package com.handwash.security;

import com.handwash.service.SessionManager;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Stores short-lived, single-use capabilities used only to upgrade a WebSocket. */
@Component
public final class WebSocketTicketRegistry {
    public static final long DEFAULT_TTL_MS = 30_000L;
    private static final int MAX_PENDING_TICKETS = 1_024;

    public record TicketIssue(String value, long expiresAtEpochMs) {}
    public record TicketGrant(String sessionId, SessionManager.AccessInfo accessInfo,
                              long expiresAtEpochMs, long expiresAtMonotonicNanos) {}

    private final Map<String, TicketGrant> tickets = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final LongSupplier monotonicNanos;
    private final LongSupplier epochMillis;
    private final long ttlMs;

    public WebSocketTicketRegistry() {
        this(System::nanoTime, System::currentTimeMillis, DEFAULT_TTL_MS);
    }

    WebSocketTicketRegistry(LongSupplier monotonicNanos, LongSupplier epochMillis, long ttlMs) {
        if (ttlMs < 1L || ttlMs > DEFAULT_TTL_MS) {
            throw new IllegalArgumentException("WebSocket ticket TTL must be between 1 and 30000 ms");
        }
        this.monotonicNanos = monotonicNanos;
        this.epochMillis = epochMillis;
        this.ttlMs = ttlMs;
    }

    public synchronized TicketIssue issue(String sessionId, SessionManager.AccessInfo accessInfo) {
        if (sessionId == null || sessionId.isBlank() || accessInfo == null) {
            throw new IllegalArgumentException("Session and authenticated access are required");
        }
        long nowNanos = monotonicNanos.getAsLong();
        long nowEpochMs = epochMillis.getAsLong();
        removeExpired(nowNanos, nowEpochMs);
        if (tickets.size() >= MAX_PENDING_TICKETS) throw new TicketCapacityException();

        long effectiveTtlMs = ttlMs;
        if (accessInfo.expiresAtEpochMs() != null) {
            effectiveTtlMs = Math.min(effectiveTtlMs,
                accessInfo.expiresAtEpochMs() - nowEpochMs);
        }
        if (accessInfo.expiresAtMonotonicNanos() != null) {
            long remainingNanos = accessInfo.expiresAtMonotonicNanos() - nowNanos;
            effectiveTtlMs = Math.min(effectiveTtlMs, remainingNanos / 1_000_000L);
        }
        if (effectiveTtlMs <= 0L) throw new TicketExpiredException();

        String value;
        do {
            byte[] entropy = new byte[32];
            secureRandom.nextBytes(entropy);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        } while (tickets.containsKey(value));

        long expiresAtEpochMs = nowEpochMs + effectiveTtlMs;
        long expiresAtMonotonicNanos = nowNanos + effectiveTtlMs * 1_000_000L;
        tickets.put(value, new TicketGrant(sessionId, accessInfo,
            expiresAtEpochMs, expiresAtMonotonicNanos));
        return new TicketIssue(value, expiresAtEpochMs);
    }

    /** Atomically consumes a ticket, including on wrong-session or expired use. */
    public TicketGrant consume(String sessionId, String value) {
        if (sessionId == null || value == null || value.isBlank()) return null;
        TicketGrant grant = tickets.remove(value);
        if (grant == null || !sessionId.equals(grant.sessionId())) return null;
        long nowNanos = monotonicNanos.getAsLong();
        long nowEpochMs = epochMillis.getAsLong();
        if (nowEpochMs >= grant.expiresAtEpochMs()
            || nowNanos - grant.expiresAtMonotonicNanos() >= 0L) return null;
        return grant;
    }

    private void removeExpired(long nowNanos, long nowEpochMs) {
        tickets.entrySet().removeIf(entry -> {
            TicketGrant grant = entry.getValue();
            return nowEpochMs >= grant.expiresAtEpochMs()
                || nowNanos - grant.expiresAtMonotonicNanos() >= 0L;
        });
    }

    static final class TicketCapacityException extends RuntimeException {
        private TicketCapacityException() { super("WebSocket ticket capacity reached"); }
    }

    static final class TicketExpiredException extends RuntimeException {
        private TicketExpiredException() { super("Session credential expired before ticket issuance"); }
    }
}
