package com.handwash.service;

import com.handwash.model.StationRecord;
import com.handwash.repository.StationOperatorAccessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("cloud")
public class StationOperatorAccessService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private final StationOperatorAccessRepository access;
    private final StationCredentialService stations;
    private final SessionAuditService audit;
    private final OperatorAccountDirectory directory;
    private final SecureRandom random = new SecureRandom();
    @org.springframework.beans.factory.annotation.Value("${handwash.security.operator-client-id:}")
    private String operatorClientId = "";

    @Autowired
    public StationOperatorAccessService(StationOperatorAccessRepository access,
                                        StationCredentialService stations,
                                        SessionAuditService audit,
                                        OperatorAccountDirectory directory) {
        this.access = access;
        this.stations = stations;
        this.audit = audit;
        this.directory = directory;
    }

    public StationOperatorAccessService(StationOperatorAccessRepository access,
                                        StationCredentialService stations,
                                        SessionAuditService audit) {
        this(access, stations, audit, new OperatorAccountDirectory() {
            @Override public CreatedAccount create(String email) {
                throw new UnavailableException("El pool de operadores no está configurado.");
            }
            @Override public void disable(String subject) { /* nothing to disable */ }
            @Override public boolean available() { return false; }
        });
    }

    /** Creates the operator's login and grants it to the station in one step; the password is shown once. */
    @Transactional
    public CreatedOperator createAccount(String stationId, String email, String adminSubject) {
        String normalizedEmail = normalizeEmail(email);
        String actor = boundedActor(adminSubject);
        if (!isUuid(stationId) || stations.activeStation(stationId) == null) {
            throw new IllegalArgumentException("El puesto no existe o está revocado.");
        }
        OperatorAccountDirectory.CreatedAccount account = directory.create(normalizedEmail);
        try {
            access.grant(account.subject(), normalizedEmail, stationId, actor, System.currentTimeMillis());
        } catch (RuntimeException grantFailed) {
            try { directory.disable(account.subject()); } catch (RuntimeException ignored) { /* best effort */ }
            throw grantFailed;
        }
        audit.recordStationEvent(null, actor, "OPERATOR_ACCOUNT_CREATED", "CREATED", stationId);
        return new CreatedOperator(account.subject(), normalizedEmail, stationId, account.temporaryPassword());
    }

    public boolean accountsAvailable() { return directory.available(); }

    @Transactional
    public IssuedOperatorCode issue(String stationId, String email, String adminSubject) {
        String normalizedEmail = normalizeEmail(email);
        String actor = boundedActor(adminSubject);
        if (!isUuid(stationId) || stations.activeStation(stationId) == null) {
            throw new IllegalArgumentException("El puesto no existe o está revocado.");
        }
        String code = randomCode();
        String codeId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        access.createCode(new StationOperatorAccessRepository.OperatorAccessCode(
            codeId, StationCredentialService.sha256(code), stationId, normalizedEmail,
            actor, now, null, null, null));
        audit.recordStationEvent(null, actor, "OPERATOR_ACCESS_CODE_ISSUED", "ISSUED", stationId);
        return new IssuedOperatorCode(codeId, code, stationId, normalizedEmail, now);
    }

    @Transactional
    public ClaimResult claim(String code, String subject, String verifiedEmail) {
        if (code == null || code.length() < 40 || code.length() > 64
            || !code.matches("[A-Za-z0-9_-]+") || !isSubject(subject)) {
            return ClaimResult.failure(ClaimOutcome.INVALID_CODE);
        }
        String email;
        try {
            email = normalizeEmail(verifiedEmail);
        } catch (IllegalArgumentException invalidEmail) {
            return ClaimResult.failure(ClaimOutcome.EMAIL_MISMATCH);
        }
        StationOperatorAccessRepository.OperatorAccessCode issued =
            access.findClaimableCodeForUpdate(StationCredentialService.sha256(code));
        if (issued == null) return ClaimResult.failure(ClaimOutcome.INVALID_CODE);
        if (!issued.email().equals(email)) return ClaimResult.failure(ClaimOutcome.EMAIL_MISMATCH);
        StationRecord station = stations.activeStation(issued.stationId());
        if (station == null) return ClaimResult.failure(ClaimOutcome.STATION_UNAVAILABLE);
        if (access.findActiveGrant(subject) != null) return ClaimResult.failure(ClaimOutcome.ALREADY_ASSIGNED);

        long now = System.currentTimeMillis();
        try {
            access.grant(subject, email, issued.stationId(), issued.createdBy(), now);
        } catch (StationOperatorAccessRepository.OperatorAlreadyAssignedException assigned) {
            return ClaimResult.failure(ClaimOutcome.ALREADY_ASSIGNED);
        }
        if (access.consumeCode(issued.codeId(), subject, now) != 1) {
            throw new IllegalStateException("El código cambió mientras se canjeaba.");
        }
        audit.recordStationEvent(null, subject, "OPERATOR_ACCESS_CODE_CLAIMED", "CLAIMED",
            issued.stationId());
        return ClaimResult.success(new GrantView(subject, email, station.stationId(),
            station.stationName(), issued.createdBy(), now, null));
    }

    public GrantView activeGrant(String subject) {
        if (!isSubject(subject)) return null;
        StationOperatorAccessRepository.OperatorGrant grant = access.findActiveGrant(subject);
        if (grant == null) return null;
        StationRecord station = stations.activeStation(grant.stationId());
        if (station == null) return null;
        return toView(grant, station);
    }

    @Transactional
    public GrantView activeGrantForUpdate(String subject) {
        if (!isSubject(subject)) return null;
        StationOperatorAccessRepository.OperatorGrant grant = access.findActiveGrantForUpdate(subject);
        if (grant == null) return null;
        StationRecord station = stations.activeStation(grant.stationId());
        if (station == null) return null;
        return toView(grant, station);
    }

    public List<GrantView> listGrants() {
        return access.listGrants().stream().map(grant -> {
            StationRecord station = stations.activeStation(grant.stationId());
            return new GrantView(grant.subject(), grant.email(), grant.stationId(),
                station == null ? "Puesto revocado" : station.stationName(), grant.grantedBy(),
                grant.grantedAtEpochMs(), grant.revokedAtEpochMs());
        }).toList();
    }

    public List<OperatorCodeView> listCodes() {
        return access.listCodes().stream().map(code -> new OperatorCodeView(code.codeId(),
            code.stationId(), code.email(), code.createdBy(), code.createdAtEpochMs(),
            code.consumedAtEpochMs(), code.consumedBy(), code.revokedAtEpochMs())).toList();
    }

    @Transactional
    public boolean revokeCode(String codeId, String adminSubject) {
        if (!isUuid(codeId)) return false;
        int changed = access.revokeCode(codeId, System.currentTimeMillis());
        if (changed == 1) audit.recordStationEvent(null, boundedActor(adminSubject),
            "OPERATOR_ACCESS_CODE_REVOKED", "REVOKED", codeId);
        return changed == 1;
    }

    @Transactional
    public String revokeGrant(String subject, String adminSubject) {
        if (!isSubject(subject)) return null;
        StationOperatorAccessRepository.OperatorGrant grant = access.findActiveGrantForUpdate(subject);
        if (grant == null) return null;
        int changed = access.revokeGrant(subject, System.currentTimeMillis());
        if (changed == 1) {
            audit.recordStationEvent(null, boundedActor(adminSubject),
                "OPERATOR_GRANT_REVOKED", "REVOKED", grant.stationId());
            // The revoked grant already blocks every request; this also ends the login and its refresh tokens.
            try { directory.disable(subject); }
            catch (RuntimeException failure) {
                audit.recordStationEvent(null, boundedActor(adminSubject),
                    "OPERATOR_ACCOUNT_DISABLE_FAILED", "FAILED", grant.stationId());
            }
        }
        return changed == 1 ? grant.stationId() : null;
    }

    /** Tokens of the operator pool; ADMIN and producer tokens never carry this client id. */
    public boolean isOperatorPoolClient(String clientId) {
        // Console-key sessions carry a synthetic client id that no Cognito token can have.
        if (com.handwash.security.SyntheticTokens.CONSOLE_CLIENT_ID.equals(clientId)) return true;
        return operatorClientId != null && !operatorClientId.isBlank() && operatorClientId.equals(clientId);
    }

    public boolean isHumanClient(String clientId, String expectedClientId) {
        return expectedClientId != null && !expectedClientId.isBlank()
            && expectedClientId.equals(clientId);
    }

    private static GrantView toView(StationOperatorAccessRepository.OperatorGrant grant,
                                    StationRecord station) {
        return new GrantView(grant.subject(), grant.email(), station.stationId(),
            station.stationName(), grant.grantedBy(), grant.grantedAtEpochMs(), grant.revokedAtEpochMs());
    }

    private String randomCode() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.length() > 254) throw new IllegalArgumentException("Correo inválido.");
        String email = value.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("Correo inválido.");
        return email;
    }

    private static String boundedActor(String value) {
        if (!isSubject(value)) throw new IllegalArgumentException("Identidad inválida.");
        return value;
    }

    private static boolean isSubject(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
            && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean isUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public enum ClaimOutcome { CLAIMED, INVALID_CODE, EMAIL_MISMATCH, STATION_UNAVAILABLE, ALREADY_ASSIGNED }
    public record CreatedOperator(String subject, String email, String stationId, String temporaryPassword) {}
    public record IssuedOperatorCode(String codeId, String code, String stationId, String email,
                                     long createdAtEpochMs) {}
    public record GrantView(String subject, String email, String stationId, String stationName,
                            String grantedBy, long grantedAtEpochMs, Long revokedAtEpochMs) {}
    public record OperatorCodeView(String codeId, String stationId, String email, String createdBy,
                                   long createdAtEpochMs, Long consumedAtEpochMs, String consumedBy,
                                   Long revokedAtEpochMs) {}
    public record ClaimResult(ClaimOutcome outcome, GrantView grant) {
        static ClaimResult success(GrantView grant) { return new ClaimResult(ClaimOutcome.CLAIMED, grant); }
        static ClaimResult failure(ClaimOutcome outcome) { return new ClaimResult(outcome, null); }
    }
}
