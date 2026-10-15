package com.handwash.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One-time codes that let a phone open the operator console of a key that is already signed in elsewhere,
 * by scanning a QR: the phone never sees the key. Codes live two minutes, in memory, and work once.
 */
@Component
public class PhoneLinkRegistry {
    static final long TTL_MS = 120_000L;
    static final long MIN_TTL_MS = 60_000L;
    static final long MAX_TTL_MS = 86_400_000L;
    static final int MAX_LIVE = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(String keyId, long expiresAt, boolean linkStation) {}

    /** What a redeemed code stands for: the key it was issued for and whether it also links the station's screen. */
    public record Redeemed(String keyId, boolean linkStation) {}

    private final Map<String, Entry> byHash = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PhoneLinkRegistry() { this(System::currentTimeMillis); }

    PhoneLinkRegistry(LongSupplier clock) { this.clock = clock; }

    public synchronized String issue(String keyId) {
        return issue(keyId, TTL_MS, false);
    }

    /** An ADMIN-issued link: lives {@code ttlMs} (clamped to 1 min..24 h), works once, may also link the station's Mac. */
    public synchronized String issue(String keyId, long ttlMs, boolean linkStation) {
        long now = clock.getAsLong();
        byHash.values().removeIf(entry -> entry.expiresAt() <= now);
        if (byHash.size() >= MAX_LIVE) throw new IllegalStateException("Demasiados enlaces de celular vigentes");
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        byHash.put(hash(code), new Entry(keyId, now + Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ttlMs)), linkStation));
        return code;
    }

    /** What the code stands for, or null when it is unknown, used or expired. */
    public synchronized Redeemed redeem(String code) {
        if (code == null || code.isBlank()) return null;
        Entry entry = byHash.remove(hash(code));
        return entry != null && entry.expiresAt() > clock.getAsLong() ? new Redeemed(entry.keyId(), entry.linkStation()) : null;
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
