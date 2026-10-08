package com.handwash.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owns opaque owner/device/dashboard credentials, rotation and expiry. Session lifecycle
 * and authorization decisions remain with SessionManager.
 */
public final class SessionCredentialRegistry {
    public static final long MAX_ACCESS_TOKEN_TTL_MS = 86_400_000L;

    public enum Role { OWNER, DEVICE, VIEWER }

    public record Access(Role role, long expiresAtEpochMs,
                         long expiresAtMonotonicNanos, long credentialRevision) {}

    public record TokenIssue(String value, long expiresAtEpochMs) {}

    private record StoredToken(TokenIssue issue, long expiresAtMonotonicNanos,
                               long credentialRevision) {}

    private record Credentials(StoredToken ownerToken, StoredToken deviceToken,
                               StoredToken viewerToken) {}

    private final Map<String, Credentials> credentialsBySession = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicLong nextCredentialRevision = new AtomicLong();
    private final LongSupplier monotonicNanos;
    private final LongSupplier epochMillis;
    private volatile long accessTokenTtlMs = MAX_ACCESS_TOKEN_TTL_MS;

    public SessionCredentialRegistry(LongSupplier monotonicNanos, LongSupplier epochMillis) {
        this.monotonicNanos = monotonicNanos;
        this.epochMillis = epochMillis;
    }

    public void configureTtl(long ttlMs) {
        if (ttlMs < 1_000L || ttlMs > MAX_ACCESS_TOKEN_TTL_MS) {
            throw new IllegalArgumentException(
                "handwash.session.access-token-ttl-ms debe estar entre 1000 y 86400000 ms");
        }
        accessTokenTtlMs = ttlMs;
    }

    public void createCredentials(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId es obligatorio para emitir credenciales");
        }
        credentialsBySession.put(sessionId,
            new Credentials(generateToken(), generateToken(), generateToken()));
    }

    public void removeCredentials(String sessionId) {
        if (sessionId != null) credentialsBySession.remove(sessionId);
    }

    public String getOwnerToken(String sessionId) {
        Credentials credentials = credentialsBySession.get(sessionId);
        if (credentials == null || isExpired(credentials.ownerToken())) return null;
        return credentials.ownerToken().issue().value();
    }

    public String getDeviceToken(String sessionId) {
        Credentials credentials = credentialsBySession.get(sessionId);
        if (credentials == null || isExpired(credentials.deviceToken())) return null;
        return credentials.deviceToken().issue().value();
    }

    public long getOwnerTokenExpirationEpochMs(String sessionId) {
        Credentials credentials = credentialsBySession.get(sessionId);
        return credentials == null ? 0L : credentials.ownerToken().issue().expiresAtEpochMs();
    }

    public long getDeviceTokenExpirationEpochMs(String sessionId) {
        Credentials credentials = credentialsBySession.get(sessionId);
        return credentials == null ? 0L : credentials.deviceToken().issue().expiresAtEpochMs();
    }

    /** Replaces only the device capability; the owner credential stays valid. */
    public TokenIssue rotateDeviceToken(String sessionId) {
        if (sessionId == null) return null;
        AtomicReference<TokenIssue> issued = new AtomicReference<>();
        credentialsBySession.computeIfPresent(sessionId, (ignored, current) -> {
            StoredToken replacement = generateToken();
            issued.set(replacement.issue());
            return new Credentials(current.ownerToken(), replacement, current.viewerToken());
        });
        return issued.get();
    }

    /** Replaces only the read-only dashboard capability. */
    public TokenIssue rotateViewerToken(String sessionId) {
        if (sessionId == null) return null;
        AtomicReference<TokenIssue> issued = new AtomicReference<>();
        credentialsBySession.computeIfPresent(sessionId, (ignored, current) -> {
            StoredToken replacement = generateToken();
            issued.set(replacement.issue());
            return new Credentials(current.ownerToken(), current.deviceToken(), replacement);
        });
        return issued.get();
    }

    /** Constant-time token comparison; expiry uses wall and monotonic clocks. */
    public Access authenticate(String sessionId, String token) {
        if (sessionId == null || token == null || token.isBlank()) return null;
        Credentials credentials = credentialsBySession.get(sessionId);
        if (credentials == null) return null;

        byte[] supplied = token.getBytes(StandardCharsets.US_ASCII);
        StoredToken owner = credentials.ownerToken();
        boolean ownerMatches = MessageDigest.isEqual(supplied,
            owner.issue().value().getBytes(StandardCharsets.US_ASCII));
        StoredToken device = credentials.deviceToken();
        boolean deviceMatches = MessageDigest.isEqual(supplied,
            device.issue().value().getBytes(StandardCharsets.US_ASCII));
        StoredToken viewer = credentials.viewerToken();
        boolean viewerMatches = MessageDigest.isEqual(supplied,
            viewer.issue().value().getBytes(StandardCharsets.US_ASCII));

        if (ownerMatches && !isExpired(owner)) return access(Role.OWNER, owner);
        if (deviceMatches && !isExpired(device)) return access(Role.DEVICE, device);
        if (viewerMatches && !isExpired(viewer)) return access(Role.VIEWER, viewer);
        return null;
    }

    public boolean isCurrent(String sessionId, Role role, Long credentialRevision) {
        if (sessionId == null || role == null || credentialRevision == null) return false;
        Credentials current = credentialsBySession.get(sessionId);
        if (current == null) return false;
        StoredToken token = switch (role) {
            case OWNER -> current.ownerToken();
            case DEVICE -> current.deviceToken();
            case VIEWER -> current.viewerToken();
        };
        return token.credentialRevision() == credentialRevision && !isExpired(token);
    }

    private Access access(Role role, StoredToken token) {
        return new Access(role, token.issue().expiresAtEpochMs(),
            token.expiresAtMonotonicNanos(), token.credentialRevision());
    }

    private StoredToken generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        long expiresAtEpochMs = epochMillis.getAsLong() + accessTokenTtlMs;
        long expiresAtMonotonicNanos = monotonicNanos.getAsLong()
            + TimeUnit.MILLISECONDS.toNanos(accessTokenTtlMs);
        TokenIssue issue = new TokenIssue(
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), expiresAtEpochMs);
        return new StoredToken(issue, expiresAtMonotonicNanos,
            nextCredentialRevision.incrementAndGet());
    }

    private boolean isExpired(StoredToken token) {
        // Wall time makes expiry include machine sleep; monotonic time prevents
        // a backward clock correction from extending a capability's lifetime.
        return epochMillis.getAsLong() >= token.issue().expiresAtEpochMs()
            || monotonicNanos.getAsLong() - token.expiresAtMonotonicNanos() >= 0L;
    }
}
