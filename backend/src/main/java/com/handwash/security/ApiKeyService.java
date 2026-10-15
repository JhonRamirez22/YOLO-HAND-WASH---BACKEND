package com.handwash.security;

import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Issues, verifies and revokes ADMIN-created API keys. The secret is shown once and only its hash is kept. */
public class ApiKeyService {
    static final String PREFIX = "hwk_";
    static final String SESSION_PREFIX = "hws_";
    private static final long SESSION_TTL_MS = 12 * 3_600_000L;
    private static final int KEY_ID_HEX = 16;
    private static final int SECRET_CHARS = 43; // 32 random bytes, base64url without padding
    private final ApiKeyRepository repository;
    private final StationCredentialService stations;
    private final StationOperatorAccessRepository grants;
    private final SessionAuditService audit;
    private static final int MAX_FAILURES = 10;
    private static final long FAILURE_WINDOW_MS = 15 * 60_000L;
    private static final int MAX_TRACKED_ADDRESSES = 10_000;
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    // The PC authenticates on every observation, so a recent acceptance is remembered briefly and last use is
    // written at most once a minute. Revoking drops the cache at once, so nothing lingers after a revoke.
    private static final long ACCEPT_CACHE_MS = 30_000L;
    private static final long USE_WRITE_INTERVAL_MS = 60_000L;
    private static final int MAX_CACHED = 512;
    private final Map<String, CachedAcceptance> accepted = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUseWrite = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository repository, StationCredentialService stations,
                         StationOperatorAccessRepository grants, SessionAuditService audit) {
        this(repository, stations, grants, audit, System::currentTimeMillis);
    }

    public ApiKeyService(ApiKeyRepository repository, StationCredentialService stations,
                         StationOperatorAccessRepository grants, SessionAuditService audit, LongSupplier clock) {
        this.repository = repository;
        this.stations = stations;
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
    }

    private static final int MAX_CONSOLE_KEYS_PER_STATION = 5;

    public IssuedKey issue(String stationId, String label, Set<ApiKeyScope> scopes, String adminSubject) {
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty() || cleanLabel.length() > 80 || cleanLabel.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("La etiqueta debe tener entre 1 y 80 caracteres válidos.");
        }
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("La key necesita al menos un permiso.");
        if (stationId == null || !stationId.matches("(?i)[0-9a-f-]{36}") || stations.activeStation(stationId) == null) {
            throw new IllegalArgumentException("El puesto no existe o está revocado.");
        }
        if (scopes.contains(ApiKeyScope.CONSOLE) && repository.findAll().stream().filter(key ->
            key.stationId().equals(stationId) && key.revokedAtEpochMs() == null
                && key.scopes().contains(ApiKeyScope.CONSOLE)).count() >= MAX_CONSOLE_KEYS_PER_STATION) {
            throw new IllegalStateException("El puesto ya tiene el máximo de keys de operador activas.");
        }
        long now = clock.getAsLong();
        String keyId = HexFormat.of().formatHex(randomBytes(KEY_ID_HEX / 2));
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        repository.insert(new ApiKeyRepository.StoredKey(keyId, stationId, cleanLabel, hash(secret), Set.copyOf(scopes),
            adminSubject, now, null, null, null, null));
        // A console key behaves like an operator assigned to the station; revoking the key revokes the assignment.
        if (scopes.contains(ApiKeyScope.CONSOLE)) grants.grant(subjectOf(keyId), cleanLabel, stationId, adminSubject, now);
        // The PC authenticated by this key is the station's producer, with no client id to copy by hand.
        if (scopes.contains(ApiKeyScope.PRODUCER)) stations.bindProducerClient(stationId, producerClientIdOf(keyId), adminSubject);
        audit.recordStationEvent(null, adminSubject, "API_KEY_ISSUED", "ISSUED", keyId);
        return new IssuedKey(keyId, PREFIX + keyId + "_" + secret);
    }

    public static String subjectOf(String keyId) { return "apikey_" + keyId; }

    public static String producerClientIdOf(String keyId) { return "apikey_" + keyId; }

    public Verification verify(String presented, String ip) {
        String address = ip == null || ip.isBlank() ? "unknown" : ip;
        // A valid key is never refused because of who else shares the address (behind the load balancer that
        // can be everyone): throttling only slows guessing, and a 256-bit secret cannot be guessed anyway.
        Parsed parsed = Parsed.of(presented);
        if (parsed == null) return rejected(address);
        long now = clock.getAsLong();
        String cacheKey = hash(presented.trim());
        CachedAcceptance recent = accepted.get(cacheKey);
        if (recent != null && now - recent.at() < ACCEPT_CACHE_MS) return recent.verification();
        ApiKeyRepository.StoredKey stored = repository.find(parsed.keyId());
        // Hash even for unknown ids so a miss costs the same as a wrong secret.
        String candidate = hash(parsed.secret());
        if (stored == null || stored.revokedAtEpochMs() != null
            || !MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                stored.secretHash().getBytes(StandardCharsets.UTF_8))) {
            return rejected(address);
        }
        Long wrote = lastUseWrite.get(stored.keyId());
        if (wrote == null || now - wrote >= USE_WRITE_INTERVAL_MS) {
            repository.markUsed(stored.keyId(), now, address);
            lastUseWrite.put(stored.keyId(), now);
        }
        Verification verification = Verification.accept(stored.keyId(), stored.stationId(), stored.label(), stored.scopes());
        if (accepted.size() >= MAX_CACHED) accepted.clear();
        accepted.put(cacheKey, new CachedAcceptance(verification, now));
        return verification;
    }

    /** Console login: the key is traded for a 12-hour session token so the key itself is never kept by the browser. */
    public Login login(String presented, String ip) {
        Verification verification = verify(presented, ip);
        if (!verification.accepted() || !verification.scopes().contains(ApiKeyScope.CONSOLE)) {
            return new Login(false, verification.locked(), null, Verification.REJECTED);
        }
        long now = clock.getAsLong();
        repository.purgeExpiredSessions(now);
        String token = SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        repository.insertSession(hash(token), verification.keyId(), now + SESSION_TTL_MS);
        audit.recordStationEvent(null, subjectOf(verification.keyId()), "API_KEY_LOGIN", "LOGIN", verification.stationId());
        return new Login(true, false, token, verification);
    }

    /**
     * A second console session for a key that is already signed in elsewhere (a phone opened by QR): the secret is
     * never involved. Refused when the key was revoked or has no console permission.
     */
    public Login sessionForKey(String keyId) {
        ApiKeyRepository.StoredKey stored = keyId == null ? null : repository.find(keyId);
        if (stored == null || stored.revokedAtEpochMs() != null || !stored.scopes().contains(ApiKeyScope.CONSOLE)) {
            return new Login(false, false, null, Verification.REJECTED);
        }
        long now = clock.getAsLong();
        repository.purgeExpiredSessions(now);
        String token = SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        repository.insertSession(hash(token), stored.keyId(), now + SESSION_TTL_MS);
        audit.recordStationEvent(null, subjectOf(stored.keyId()), "API_KEY_LOGIN", "LOGIN", stored.stationId());
        return new Login(true, false, token,
            Verification.accept(stored.keyId(), stored.stationId(), stored.label(), stored.scopes()));
    }

    public Verification authenticateSession(String token) {
        if (token == null || !token.matches(SESSION_PREFIX + "[A-Za-z0-9_-]{43}")) return Verification.REJECTED;
        String keyId = repository.findLiveSessionKey(hash(token), clock.getAsLong());
        if (keyId == null) return Verification.REJECTED;
        ApiKeyRepository.StoredKey stored = repository.find(keyId);
        if (stored == null || stored.revokedAtEpochMs() != null || !stored.scopes().contains(ApiKeyScope.CONSOLE)) {
            return Verification.REJECTED;
        }
        return Verification.accept(stored.keyId(), stored.stationId(), stored.label(), stored.scopes());
    }

    public List<KeyView> list() {
        return repository.findAll().stream().map(key -> new KeyView(key.keyId(), key.stationId(), key.label(),
            key.scopes(), key.createdBy(), key.createdAtEpochMs(), key.lastUsedAtEpochMs(), key.lastUsedIp(),
            key.revokedAtEpochMs())).toList();
    }

    private boolean isLocked(String address) {
        Failures seen = failures.get(address);
        if (seen == null) return false;
        if (clock.getAsLong() - seen.windowStart() > FAILURE_WINDOW_MS) {
            failures.remove(address);
            return false;
        }
        return seen.count() >= MAX_FAILURES;
    }

    private Verification rejected(String address) {
        long now = clock.getAsLong();
        if (failures.size() >= MAX_TRACKED_ADDRESSES) {
            failures.entrySet().removeIf(entry -> now - entry.getValue().windowStart() > FAILURE_WINDOW_MS);
        }
        failures.merge(address, new Failures(now, 1), (old, ignored) ->
            now - old.windowStart() > FAILURE_WINDOW_MS ? new Failures(now, 1) : new Failures(old.windowStart(), old.count() + 1));
        return isLocked(address) ? Verification.LOCKED : Verification.REJECTED;
    }

    public boolean revoke(String keyId, String adminSubject) {
        if (keyId == null || !keyId.matches("[0-9a-f]{16}")) return false;
        ApiKeyRepository.StoredKey stored = repository.find(keyId);
        long now = clock.getAsLong();
        if (repository.revoke(keyId, now, adminSubject) != 1) return false;
        repository.deleteSessionsOfKey(keyId);
        accepted.values().removeIf(cached -> keyId.equals(cached.verification().keyId()));
        lastUseWrite.remove(keyId);
        grants.revokeGrant(subjectOf(keyId), now);
        stations.clearProducerClientIfBoundTo(stored.stationId(), producerClientIdOf(keyId));
        audit.recordStationEvent(null, adminSubject, "API_KEY_REVOKED", "REVOKED", keyId);
        return true;
    }

    /** A revoked station must not keep working keys: revokes every key of the station, its sessions and bindings. */
    public int revokeAllForStation(String stationId, String adminSubject) {
        int revoked = 0;
        for (ApiKeyRepository.StoredKey key : repository.findAll()) {
            if (key.stationId().equals(stationId) && key.revokedAtEpochMs() == null && revoke(key.keyId(), adminSubject)) revoked++;
        }
        return revoked;
    }

    private record Failures(long windowStart, int count) {}

    private record CachedAcceptance(Verification verification, long at) {}

    private record Parsed(String keyId, String secret) {
        static Parsed of(String key) {
            if (key == null || key.length() != PREFIX.length() + KEY_ID_HEX + 1 + SECRET_CHARS
                || !key.startsWith(PREFIX) || key.charAt(PREFIX.length() + KEY_ID_HEX) != '_') return null;
            String keyId = key.substring(PREFIX.length(), PREFIX.length() + KEY_ID_HEX);
            String secret = key.substring(PREFIX.length() + KEY_ID_HEX + 1);
            if (!keyId.matches("[0-9a-f]{16}") || !secret.matches("[A-Za-z0-9_-]{43}")) return null;
            return new Parsed(keyId, secret);
        }
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    public record Login(boolean accepted, boolean locked, String sessionToken, Verification verification) {}

    public record IssuedKey(String keyId, String key) {}

    /** What ADMIN sees about a key: never the secret or its hash. */
    public record KeyView(String keyId, String stationId, String label, Set<ApiKeyScope> scopes, String createdBy,
                          long createdAtEpochMs, Long lastUsedAtEpochMs, String lastUsedIp, Long revokedAtEpochMs) {}

    public record Verification(boolean accepted, boolean locked, String keyId, String stationId, String label,
                               Set<ApiKeyScope> scopes) {
        static final Verification REJECTED = new Verification(false, false, null, null, null, Set.of());
        static final Verification LOCKED = new Verification(false, true, null, null, null, Set.of());

        static Verification accept(String keyId, String stationId, String label, Set<ApiKeyScope> scopes) {
            return new Verification(true, false, keyId, stationId, label, scopes);
        }
    }
}
