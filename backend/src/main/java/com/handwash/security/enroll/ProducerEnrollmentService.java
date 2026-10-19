package com.handwash.security.enroll;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A PC with YOLO announces itself and waits; ADMIN approves it (and picks the station) with one click.
 * The issued key reaches that PC exactly once and lives only in memory: nothing secret is persisted here, a
 * restart simply makes the PC announce itself again. The short code is what ADMIN compares with the PC's screen.
 */
@Service
public class ProducerEnrollmentService {
    static final long TTL_MS = 600_000L;
    static final int MAX_PENDING = 20;
    static final int MAX_PER_ADDRESS = 3;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Started(String deviceCode, String userCode, int expiresInSeconds) {}
    public record PendingView(String userCode, String deviceName, String address, long ageSeconds) {}
    public record PollResult(String status, String key) {}

    private static final class Entry {
        final String userCode;
        final String deviceName;
        final String address;
        final long createdAt;
        volatile String approvedKey;
        Entry(String userCode, String deviceName, String address, long createdAt) {
            this.userCode = userCode; this.deviceName = deviceName; this.address = address; this.createdAt = createdAt;
        }
    }

    private final Map<String, Entry> byDeviceHash = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ProducerEnrollmentService() { this(System::currentTimeMillis); }

    ProducerEnrollmentService(LongSupplier clock) { this.clock = clock; }

    public synchronized Started start(String rawName, String address) {
        purgeExpired();
        String name = clean(rawName);
        if (name.isEmpty()) throw new IllegalArgumentException("El nombre del PC es obligatorio");
        String from = address == null ? "unknown" : address;
        long same = byDeviceHash.values().stream().filter(entry -> entry.address.equals(from)).count();
        if (byDeviceHash.size() >= MAX_PENDING || same >= MAX_PER_ADDRESS) {
            throw new IllegalStateException("Demasiadas solicitudes de PC pendientes");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String deviceCode = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String userCode = newUserCode();
        byDeviceHash.put(hash(deviceCode), new Entry(userCode, name, from, clock.getAsLong()));
        return new Started(deviceCode, userCode, (int) (TTL_MS / 1000));
    }

    public synchronized PollResult poll(String deviceCode) {
        purgeExpired();
        if (deviceCode == null || deviceCode.isBlank()) return new PollResult("expired", null);
        String hashed = hash(deviceCode);
        Entry entry = byDeviceHash.get(hashed);
        if (entry == null) return new PollResult("expired", null);
        if (entry.approvedKey == null) return new PollResult("pending", null);
        byDeviceHash.remove(hashed);
        return new PollResult("approved", entry.approvedKey);
    }

    public synchronized List<PendingView> pending() {
        purgeExpired();
        long now = clock.getAsLong();
        List<PendingView> views = new ArrayList<>();
        for (Entry entry : byDeviceHash.values()) {
            if (entry.approvedKey == null) {
                views.add(new PendingView(entry.userCode, entry.deviceName, entry.address, (now - entry.createdAt) / 1000));
            }
        }
        views.sort((a, b) -> Long.compare(a.ageSeconds(), b.ageSeconds()));
        return views;
    }

    /** Issues the key (only when a live request exists) and parks it for the waiting PC. */
    public synchronized boolean approve(String userCode, Function<String, String> issueKeyForDeviceName) {
        purgeExpired();
        Entry entry = find(userCode);
        if (entry == null || entry.approvedKey != null) return false;
        entry.approvedKey = issueKeyForDeviceName.apply(entry.deviceName);
        return true;
    }

    public synchronized boolean reject(String userCode) {
        purgeExpired();
        String wanted = normalize(userCode);
        return byDeviceHash.entrySet().removeIf(item -> item.getValue().userCode.equals(wanted)
            && item.getValue().approvedKey == null);
    }

    private Entry find(String userCode) {
        String wanted = normalize(userCode);
        return byDeviceHash.values().stream().filter(entry -> entry.userCode.equals(wanted)).findFirst().orElse(null);
    }

    private void purgeExpired() {
        long now = clock.getAsLong();
        byDeviceHash.values().removeIf(entry -> now - entry.createdAt >= TTL_MS);
    }

    private String newUserCode() {
        for (;;) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i == 4) code.append('-');
                code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            if (find(code.toString()) == null) return code.toString();
        }
    }

    private static String normalize(String userCode) {
        return userCode == null ? "" : userCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("[\\p{Cntrl}<>\"'`]", " ").replaceAll("\\s+", " ").trim();
        return text.length() > 40 ? text.substring(0, 40).trim() : text;
    }

    private static String hash(String deviceCode) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(deviceCode.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
