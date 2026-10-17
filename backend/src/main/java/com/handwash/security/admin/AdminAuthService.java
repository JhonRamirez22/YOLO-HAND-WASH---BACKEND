package com.handwash.security.admin;

import com.handwash.service.SessionAuditService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** Local ADMIN accounts: password + TOTP, 8-hour sessions, account lock-outs. Replaces Cognito + MFA on-premises. */
public class AdminAuthService {
    public static final String TOKEN_PREFIX = "hwa_";
    private static final long SESSION_TTL_MS = 8 * 3_600_000L;
    private static final long WINDOW_MS = 15 * 60_000L;
    private static final int MAX_PASSWORD_FAILURES = 10;
    private static final int MAX_TOTP_FAILURES = 5;
    private static final Set<String> COMMON = Set.of("password1234", "123456789012", "qwertyuiop12", "administrator");
    private static final String ISSUER = "HandWash";

    public enum Status { SUCCESS, ENROLLMENT_REQUIRED, INVALID, LOCKED }

    public record LoginOutcome(Status status, String token, long expiresAtEpochMs, String username, String totpSecret,
                               String otpauthUri, long retryAfterSeconds) {
        static LoginOutcome of(Status status) { return new LoginOutcome(status, null, 0, null, null, null, 0); }
    }

    public record AdminPrincipal(long accountId, String username) {
        public String subject() { return "admin:" + accountId; }
    }

    private final AdminAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final TotpSecretVault vault;
    private final SessionAuditService audit;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    /** When false the password alone is enough (no code, no enrollment); the stored secrets are left untouched. */
    private final boolean requireTotp;

    public AdminAuthService(AdminAccountRepository accounts, PasswordEncoder encoder, TotpSecretVault vault,
                            SessionAuditService audit, LongSupplier clock) {
        this(accounts, encoder, vault, audit, clock, true);
    }

    public AdminAuthService(AdminAccountRepository accounts, PasswordEncoder encoder, TotpSecretVault vault,
                            SessionAuditService audit, LongSupplier clock, boolean requireTotp) {
        this.requireTotp = requireTotp;
        this.accounts = accounts;
        this.encoder = encoder;
        this.vault = vault;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /** Creates the first account (password + a fresh TOTP secret to be enrolled at first login) only when none exists. */
    public void bootstrap(String username, String password) {
        validatePassword(password);
        String name = normalize(username);
        if (accounts.count() > 0) return;
        accounts.insert(name, encoder.encode(password), vault.encrypt(Totp.newSecret()), clock.getAsLong());
        audit.recordStationEvent(null, "admin:bootstrap", "ADMIN_ACCOUNT_BOOTSTRAPPED", "CREATED", name);
    }

    public LoginOutcome login(String username, String password, String totp, String ip) {
        long now = clock.getAsLong();
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        AdminAccountRepository.Account account = name.isEmpty() || name.length() > 64 ? null : accounts.findByUsername(name);
        if (account == null || account.disabled()) {
            encoder.matches(password == null ? "" : password, dummyHash);  // same cost as a real check
            return LoginOutcome.of(Status.INVALID);
        }
        if (account.lockedUntilMs() != null && account.lockedUntilMs() > now) {
            return new LoginOutcome(Status.LOCKED, null, 0, null, null, null, (account.lockedUntilMs() - now + 999) / 1000);
        }
        // An expired window or lock starts from a clean slate.
        int passwordFailures = windowOpen(account, now) ? account.passwordFailures() : 0;
        int totpFailures = windowOpen(account, now) ? account.totpFailures() : 0;
        Long windowStart = windowOpen(account, now) ? account.failureWindowStartMs() : null;

        if (password == null || !encoder.matches(password, account.passwordHash())) {
            passwordFailures++;
            Long lock = passwordFailures >= MAX_PASSWORD_FAILURES ? now + WINDOW_MS : null;
            accounts.saveFailures(account.id(), lock == null ? passwordFailures : 0, totpFailures, windowStart == null ? now : windowStart, lock);
            if (lock != null) audit.recordStationEvent(null, "admin:" + account.id(), "ADMIN_LOGIN_LOCKED", "LOCKED", "PASSWORD");
            return LoginOutcome.of(Status.INVALID);
        }

        if (!requireTotp) return signedIn(account, -1, false, now);

        byte[] secret = vault.decrypt(account.totpSecretEnc());
        if (!account.totpConfirmed() && (totp == null || totp.isBlank())) {
            String base32 = Totp.toBase32(secret);
            return new LoginOutcome(Status.ENROLLMENT_REQUIRED, null, 0, account.username(), base32,
                Totp.otpauthUri(ISSUER, account.username(), base32), 0);
        }
        long step = totp == null ? -1 : Totp.verify(secret, totp.trim(), now / 1000, account.lastTotpStep());
        if (step < 0) {
            if (totp != null && !totp.isBlank()) {
                totpFailures++;
                Long lock = totpFailures >= MAX_TOTP_FAILURES ? now + WINDOW_MS : null;
                accounts.saveFailures(account.id(), passwordFailures, lock == null ? totpFailures : 0, windowStart == null ? now : windowStart, lock);
                if (lock != null) audit.recordStationEvent(null, "admin:" + account.id(), "ADMIN_LOGIN_LOCKED", "LOCKED", "TOTP");
            }
            return LoginOutcome.of(Status.INVALID);
        }
        return signedIn(account, step, !account.totpConfirmed(), now);
    }

    private LoginOutcome signedIn(AdminAccountRepository.Account account, long step, boolean confirming, long now) {
        if (requireTotp) accounts.recordSuccess(account.id(), step, confirming);
        accounts.purgeExpiredSessions(now);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        long expires = now + SESSION_TTL_MS;
        accounts.insertSession(hash(token), account.id(), expires);
        audit.recordStationEvent(null, "admin:" + account.id(), "ADMIN_LOGIN", confirming ? "ENROLLED" : "LOGIN", account.username());
        return new LoginOutcome(Status.SUCCESS, token, expires, account.username(), null, null, 0);
    }

    public Optional<AdminPrincipal> authenticate(String token) {
        if (token == null || !token.matches(TOKEN_PREFIX + "[A-Za-z0-9_-]{43}")) return Optional.empty();
        long id = accounts.findLiveSession(hash(token), clock.getAsLong());
        if (id < 0) return Optional.empty();
        AdminAccountRepository.Account account = accounts.findById(id);
        return account == null || account.disabled() ? Optional.empty() : Optional.of(new AdminPrincipal(id, account.username()));
    }

    public void logout(String token) {
        if (token != null) accounts.deleteSession(hash(token));
    }

    /** @return false when the current password is wrong; every session of the account ends on success. */
    public boolean changePassword(long accountId, String current, String replacement) {
        validatePassword(replacement);
        AdminAccountRepository.Account account = accounts.findById(accountId);
        if (account == null || current == null || !encoder.matches(current, account.passwordHash())) return false;
        accounts.updatePassword(accountId, encoder.encode(replacement));
        accounts.deleteSessionsOfAccount(accountId);
        audit.recordStationEvent(null, "admin:" + accountId, "ADMIN_PASSWORD_CHANGED", "CHANGED", account.username());
        return true;
    }

    String currentSecretForTests(String username) {
        return Totp.toBase32(vault.decrypt(accounts.findByUsername(username).totpSecretEnc()));
    }

    private boolean windowOpen(AdminAccountRepository.Account account, long now) {
        return account.failureWindowStartMs() != null && now - account.failureWindowStartMs() < WINDOW_MS;
    }

    private static String normalize(String username) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9._-]{3,64}")) throw new IllegalArgumentException("Usuario inválido");
        return name;
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128
            || password.chars().distinct().count() < 5 || COMMON.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("La contraseña debe tener entre 12 y 128 caracteres y no ser trivial");
        }
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
