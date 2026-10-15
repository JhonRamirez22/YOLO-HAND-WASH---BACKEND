package com.handwash.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Pairs a phone camera with a wash station: the panel shows a QR holding a one-time code (2 minutes); the phone
 * trades it for a camera-only token (12 hours) that can fetch video tickets and nothing else. One camera per
 * station: the last scan wins and the previous camera is told it was replaced. Everything lives in memory and
 * is stored hashed.
 */
@Component
public class CameraPairingRegistry {
    static final long CODE_TTL_MS = 120_000L;
    static final long TOKEN_TTL_MS = 12 * 3_600_000L;
    static final int MAX_LIVE_CODES = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a phone receives when it redeems a code. */
    public record PairedCamera(String token, String stationId) {}

    private record Code(String stationId, long expiresAt) {}
    private record Token(String stationId, long expiresAt) {}

    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, String> currentTokenHashByStation = new ConcurrentHashMap<>();
    private final Map<String, Boolean> replaced = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CameraPairingRegistry() { this(System::currentTimeMillis); }

    CameraPairingRegistry(LongSupplier clock) { this.clock = clock; }

    public synchronized String issueCode(String stationId) {
        long now = clock.getAsLong();
        codes.values().removeIf(code -> code.expiresAt() <= now);
        if (codes.size() >= MAX_LIVE_CODES) throw new IllegalStateException("Demasiados códigos de cámara vigentes");
        String code = random();
        codes.put(hash(code), new Code(stationId, now + CODE_TTL_MS));
        return code;
    }

    /** The paired camera, or null when the code is unknown, used or expired. */
    public synchronized PairedCamera redeem(String code) {
        if (code == null || code.isBlank()) return null;
        Code entry = codes.remove(hash(code));
        long now = clock.getAsLong();
        if (entry == null || entry.expiresAt() <= now) return null;
        String token = random();
        String tokenHash = hash(token);
        String previous = currentTokenHashByStation.put(entry.stationId(), tokenHash);
        if (previous != null) {
            tokens.remove(previous);
            replaced.put(previous, Boolean.TRUE);
        }
        tokens.put(tokenHash, new Token(entry.stationId(), now + TOKEN_TTL_MS));
        return new PairedCamera(token, entry.stationId());
    }

    /** The station a live camera token belongs to, or null (unknown, expired or replaced). */
    public String authenticate(String token) {
        if (token == null || token.isBlank()) return null;
        Token entry = tokens.get(hash(token));
        return entry != null && entry.expiresAt() > clock.getAsLong() ? entry.stationId() : null;
    }

    /** True when this token was a camera that another scan has since replaced. */
    public boolean isReplaced(String token) {
        return token != null && replaced.containsKey(hash(token));
    }

    private static String random() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
