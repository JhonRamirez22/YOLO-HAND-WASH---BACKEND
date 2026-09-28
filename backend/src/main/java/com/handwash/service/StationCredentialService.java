package com.handwash.service;

import com.handwash.model.StationRecord;
import com.handwash.repository.StationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Profile("cloud")
public class StationCredentialService {
    private static final long ENROLLMENT_TTL_MS = 10 * 60 * 1000L;
    private static final int TOKEN_BYTES = 32;
    private final StationRepository stations;
    private final SessionAuditService audit;
    private final SecureRandom random;

    @Autowired
    public StationCredentialService(StationRepository stations, SessionAuditService audit) {
        this(stations, audit, new SecureRandom());
    }

    StationCredentialService(StationRepository stations, SessionAuditService audit, SecureRandom random) {
        this.stations = stations;
        this.audit = audit;
        this.random = random;
    }

    @Transactional
    public IssuedEnrollmentCode issueEnrollmentCode(String stationName, String adminSubject) {
        String normalizedName = normalizeStationName(stationName);
        String actor = boundedActor(adminSubject);
        String code = randomToken(24);
        long now = System.currentTimeMillis();
        long expiresAt = now + ENROLLMENT_TTL_MS;
        stations.createEnrollmentCode(sha256(code), normalizedName, actor, now, expiresAt);
        audit.recordStationEvent(null, actor, "STATION_ENROLLMENT_CODE_ISSUED",
            "ISSUED", "ONE_TIME_10_MINUTES");
        return new IssuedEnrollmentCode(code, expiresAt);
    }

    private static final long ACTIVATION_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Creates the station right away so its producer and operators can be attached before the Mac exists.
     * The returned activation token is shown once; only its hash is stored.
     */
    @Transactional
    public ProvisionedStation provision(String stationName, String producerClientId, String adminSubject) {
        String name = normalizeStationName(stationName);
        String actor = boundedActor(adminSubject);
        if (producerClientId != null && !producerClientId.isBlank()
            && !producerClientId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("clientId inválido");
        }
        String producer = producerClientId == null || producerClientId.isBlank() ? null : producerClientId;
        long now = System.currentTimeMillis();
        String stationId = UUID.randomUUID().toString();
        String token = randomToken(TOKEN_BYTES);
        long expiresAt = now + ACTIVATION_TTL_MS;
        StationRecord station = new StationRecord(stationId, name, now, actor, null, null, null, null, producer);
        // The placeholder credential is never disclosed, so nobody can authenticate before activation.
        stations.createPendingStation(station, sha256(randomToken(TOKEN_BYTES)), sha256(token), expiresAt);
        audit.recordStationEvent(null, actor, "STATION_PROVISIONED", "PROVISIONED", stationId);
        return new ProvisionedStation(station, token, expiresAt);
    }

    @Transactional
    public IssuedEnrollmentCode reissueActivation(String stationId, String adminSubject) {
        if (!isUuid(stationId)) throw new IllegalArgumentException("Puesto inválido");
        String actor = boundedActor(adminSubject);
        String token = randomToken(TOKEN_BYTES);
        long expiresAt = System.currentTimeMillis() + ACTIVATION_TTL_MS;
        if (stations.reissueActivation(stationId, sha256(token), expiresAt) != 1) {
            throw new IllegalArgumentException("El puesto no existe, está revocado o ya fue activado");
        }
        audit.recordStationEvent(null, actor, "STATION_ACTIVATION_REISSUED", "REISSUED", stationId);
        return new IssuedEnrollmentCode(token, expiresAt);
    }

    @Transactional
    public EnrolledStation activate(String token) {
        if (token == null || token.length() < 30 || token.length() > 100
            || !token.matches("[A-Za-z0-9_-]+")) return null;
        String credential = randomToken(TOKEN_BYTES);
        StationRecord station = stations.activate(sha256(token), sha256(credential), System.currentTimeMillis());
        if (station == null) return null;
        audit.recordStationEvent(null, station.stationId(), "STATION_ACTIVATED", "ACTIVATED",
            "ACTIVATION_TOKEN_REDEEMED");
        return new EnrolledStation(station, credential);
    }

    @Transactional
    public EnrolledStation enroll(String code) {
        if (code == null || code.length() < 30 || code.length() > 100) return null;
        StationRepository.EnrollmentCode enrollment = stations.consumeEnrollmentCode(
            sha256(code), System.currentTimeMillis());
        if (enrollment == null) return null;
        long now = System.currentTimeMillis();
        String stationId = UUID.randomUUID().toString();
        String credential = randomToken(TOKEN_BYTES);
        StationRecord station = new StationRecord(stationId, enrollment.stationName(), now,
            enrollment.createdBy(), null, null, null, null);
        stations.createStation(station, sha256(credential));
        audit.recordStationEvent(null, stationId, "STATION_ENROLLED", "ENROLLED",
            "ENROLLMENT_CODE_REDEEMED");
        return new EnrolledStation(station, credential);
    }

    public StationRecord authenticate(String credential) {
        if (credential == null || credential.length() < 40 || credential.length() > 64
            || !credential.matches("[A-Za-z0-9_-]+")) return null;
        return stations.findActiveByCredentialHash(sha256(credential));
    }

    public List<StationRecord> listStations() {
        return stations.findAll();
    }

    /** When each station's Mac was linked; null while the ADMIN-created station still waits for activation. */
    public java.util.Map<String, Long> activationTimes() {
        return stations.activationTimes();
    }

    public StationRecord activeStation(String stationId) {
        return stationId == null ? null : stations.findActiveById(stationId);
    }

    /** The station a producer identity (e.g. an API key's apikey_<id>) is bound to; null when none or revoked. */
    public StationRecord activeStationByProducerClient(String producerClientId) {
        return producerClientId == null || producerClientId.isBlank() ? null
            : stations.findActiveByProducerClient(producerClientId);
    }

    @Transactional
    public boolean revoke(String stationId, String adminSubject) {
        if (stationId == null || !isUuid(stationId)) return false;
        String actor = boundedActor(adminSubject);
        int updated = stations.revoke(stationId, System.currentTimeMillis());
        if (updated == 1) {
            audit.recordStationEvent(null, actor, "STATION_REVOKED", "REVOKED", stationId);
        }
        return updated == 1;
    }

    @Transactional
    public void bindProducerClient(String stationId, String clientId, String adminSubject) {
        if (stationId == null || !isUuid(stationId)) throw new IllegalArgumentException("Puesto inválido");
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("clientId inválido");
        }
        String actor = boundedActor(adminSubject);
        if (stations.bindProducerClient(stationId, clientId) != 1) {
            throw new IllegalArgumentException("Puesto inexistente o revocado");
        }
        audit.recordStationEvent(null, actor, "STATION_PRODUCER_BOUND", "BOUND", stationId);
    }

    public void clearProducerClientIfBoundTo(String stationId, String clientId) {
        if (stationId != null && clientId != null) stations.clearProducerClient(stationId, clientId);
    }

    /**
     * Gives the Mac of an authenticated key a device credential. Once a Mac is linked, another device cannot take
     * the station over by accident: it needs {@code force}, an explicit decision that invalidates the old credential.
     */
    @Transactional
    public LinkResult linkStationByKey(String stationId, String keySubject, boolean force) {
        if (!isUuid(stationId) || stations.findActiveById(stationId) == null) return null;
        String credential = randomToken(TOKEN_BYTES);
        if (stations.replaceCredential(stationId, sha256(credential), System.currentTimeMillis(), force) != 1) {
            return new LinkResult(null, true);
        }
        audit.recordStationEvent(null, boundedActor(keySubject), "STATION_LINKED_BY_KEY", force ? "REPLACED" : "LINKED", stationId);
        return new LinkResult(credential, false);
    }

    public boolean reserveSession(String stationId, String sessionId, String requestId) {
        return stations.reserveSession(stationId, sessionId, requestId, System.currentTimeMillis()) == 1;
    }

    public void releaseSession(String sessionId) {
        stations.releaseSession(sessionId);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }

    private String randomToken(int bytes) {
        byte[] randomBytes = new byte[bytes];
        random.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static String normalizeStationName(String value) {
        if (value == null) throw new IllegalArgumentException("stationName es obligatorio");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 80 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("stationName debe tener entre 1 y 80 caracteres válidos");
        }
        return normalized;
    }

    private static String boundedActor(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("La identidad de auditoría es inválida");
        }
        return value;
    }

    private static boolean isUuid(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public record IssuedEnrollmentCode(String code, long expiresAtEpochMs) {}
    public record LinkResult(String credential, boolean alreadyLinked) {}
    public record ProvisionedStation(StationRecord station, String activationToken, long expiresAtEpochMs) {}
    public record EnrolledStation(StationRecord station, String credential) {}
}
