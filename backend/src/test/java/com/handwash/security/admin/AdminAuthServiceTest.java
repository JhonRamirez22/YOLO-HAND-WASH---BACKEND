package com.handwash.security.admin;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.service.SessionAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seam: AdminAuthService. Local ADMIN accounts with a password and a TOTP code replace Cognito + MFA on-premises. */
class AdminAuthServiceTest {
    private static final String PASSWORD = "Correct-Horse-9";
    private final AtomicLong nowMs = new AtomicLong(1_760_000_000_000L);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminAuthService auth;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        var vault = new TotpSecretVault(new byte[32]);
        auth = new AdminAuthService(new AdminAccountRepository(jdbc), new BCryptPasswordEncoder(4), vault,
            new SessionAuditService(new SessionAuditRepository(jdbc)), nowMs::get);
        auth.bootstrap("jhon", PASSWORD);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private long seconds() { return nowMs.get() / 1000; }

    /** Completes the first login: password -> secret shown once -> confirm with a code from that secret. */
    private AdminAuthService.LoginOutcome enrolled() {
        var first = auth.login("jhon", PASSWORD, null, "203.0.113.5");
        assertEquals(AdminAuthService.Status.ENROLLMENT_REQUIRED, first.status());
        byte[] secret = Totp.fromBase32(first.totpSecret());
        return auth.login("jhon", PASSWORD, Totp.code(secret, seconds() / 30), "203.0.113.5");
    }

    private byte[] secretOf(String username) {
        return Totp.fromBase32(auth.currentSecretForTests(username));
    }

    @Test
    void withoutTheSecondFactorThePasswordAloneSignsInAtOnceAndNeverAsksToEnroll() {
        var open = new AdminAuthService(new AdminAccountRepository(jdbc), new BCryptPasswordEncoder(4), new TotpSecretVault(new byte[32]),
            new SessionAuditService(new SessionAuditRepository(jdbc)), nowMs::get, false);

        var ok = open.login("jhon", PASSWORD, null, "203.0.113.5");

        assertEquals(AdminAuthService.Status.SUCCESS, ok.status());
        assertTrue(open.authenticate(ok.token()).isPresent());
        assertEquals(AdminAuthService.Status.INVALID, open.login("jhon", "wrong-password-1", null, "203.0.113.5").status());
        assertEquals(AdminAuthService.Status.SUCCESS, open.login("jhon", PASSWORD, "000000", "203.0.113.5").status(),
            "a stale code typed by habit does not block the password");
    }

    @Test
    void bootstrapCreatesOneAccountOnlyAndStoresNothingInClear() {
        auth.bootstrap("other", "Another-Pass-77");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_accounts", Integer.class));
        String hash = jdbc.queryForObject("SELECT password_hash FROM admin_accounts", String.class);
        assertNotEquals(PASSWORD, hash);
        assertTrue(hash.startsWith("$2"));
        assertThrows(IllegalArgumentException.class, () -> new AdminAuthService(new AdminAccountRepository(jdbc),
            new BCryptPasswordEncoder(4), new TotpSecretVault(new byte[32]),
            new SessionAuditService(new SessionAuditRepository(jdbc)), nowMs::get).bootstrap("weak", "short"));
    }

    @Test
    void theFirstLoginShowsTheTotpSecretOnlyAfterAGoodPasswordAndConfirmsItWithTheFirstCode() {
        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", "wrong-password-1", null, "203.0.113.5").status());

        var first = auth.login("jhon", PASSWORD, null, "203.0.113.5");
        assertEquals(AdminAuthService.Status.ENROLLMENT_REQUIRED, first.status());
        assertTrue(first.otpauthUri().startsWith("otpauth://totp/HandWash:jhon?secret=" + first.totpSecret()));
        assertEquals(first.totpSecret(), auth.login("jhon", PASSWORD, null, "203.0.113.5").totpSecret(), "stable until confirmed");

        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", PASSWORD, "000000", "203.0.113.5").status());
        var done = enrolled();
        assertEquals(AdminAuthService.Status.SUCCESS, done.status());
        assertTrue(done.token().startsWith("hwa_"));
    }

    @Test
    void afterEnrollmentLoginNeedsPasswordAndAFreshCodeAndACodeCannotBeReplayed() {
        enrolled();
        nowMs.addAndGet(31_000);
        byte[] secret = secretOf("jhon");
        String code = Totp.code(secret, seconds() / 30);

        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", PASSWORD, null, "203.0.113.5").status());
        assertEquals(AdminAuthService.Status.SUCCESS, auth.login("jhon", PASSWORD, code, "203.0.113.5").status());
        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", PASSWORD, code, "203.0.113.5").status(), "replay");
        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", "Another-Pass-77", Totp.code(secret, seconds() / 30 + 1), "203.0.113.5").status());
        assertEquals(AdminAuthService.Status.INVALID, auth.login("nobody", PASSWORD, code, "203.0.113.5").status());
    }

    @Test
    void tenWrongPasswordsLockTheAccountForFifteenMinutesEvenForTheRightOne() {
        enrolled();
        for (int i = 0; i < 10; i++) auth.login("jhon", "wrong-password-" + i, null, "198.51.100.9");

        var locked = auth.login("jhon", PASSWORD, null, "203.0.113.5");
        assertEquals(AdminAuthService.Status.LOCKED, locked.status());
        assertTrue(locked.retryAfterSeconds() > 0 && locked.retryAfterSeconds() <= 900);

        nowMs.addAndGet(16 * 60_000L);
        assertEquals(AdminAuthService.Status.SUCCESS, auth.login("jhon", PASSWORD,
            Totp.code(secretOf("jhon"), seconds() / 30), "203.0.113.5").status());
    }

    @Test
    void fiveWrongCodesAfterTheRightPasswordLockTheAccount() {
        enrolled();
        nowMs.addAndGet(31_000);
        for (int i = 0; i < 5; i++) auth.login("jhon", PASSWORD, "00000" + i, "203.0.113.5");

        assertEquals(AdminAuthService.Status.LOCKED, auth.login("jhon", PASSWORD,
            Totp.code(secretOf("jhon"), seconds() / 30), "203.0.113.5").status());
    }

    @Test
    void sessionsAuthenticateExpireAndEndOnLogoutOrPasswordChange() {
        var login = enrolled();
        var principal = auth.authenticate(login.token());
        assertTrue(principal.isPresent());
        assertEquals("jhon", principal.get().username());
        assertTrue(principal.get().subject().startsWith("admin:"));
        assertTrue(auth.authenticate("hwa_" + "A".repeat(43)).isEmpty());
        assertTrue(auth.authenticate(null).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_sessions WHERE token_hash LIKE ?", Integer.class,
            "%" + login.token().substring(4) + "%"), "the token itself is not stored");

        auth.logout(login.token());
        assertTrue(auth.authenticate(login.token()).isEmpty());

        nowMs.addAndGet(31_000);
        var second = auth.login("jhon", PASSWORD, Totp.code(secretOf("jhon"), seconds() / 30), "203.0.113.5");
        nowMs.addAndGet(8 * 3_600_000L + 1);
        assertTrue(auth.authenticate(second.token()).isEmpty(), "expired after 8 h");
    }

    @Test
    void changingThePasswordNeedsTheOldOneAndEndsEverySession() {
        var login = enrolled();
        long account = auth.authenticate(login.token()).orElseThrow().accountId();

        assertFalse(auth.changePassword(account, "not-the-old-pass-1", "Brand-New-Pass-42"));
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(account, PASSWORD, "short"));
        assertTrue(auth.changePassword(account, PASSWORD, "Brand-New-Pass-42"));

        assertTrue(auth.authenticate(login.token()).isEmpty());
        nowMs.addAndGet(31_000);
        assertEquals(AdminAuthService.Status.INVALID, auth.login("jhon", PASSWORD, Totp.code(secretOf("jhon"), seconds() / 30), "203.0.113.5").status());
        assertEquals(AdminAuthService.Status.SUCCESS, auth.login("jhon", "Brand-New-Pass-42", Totp.code(secretOf("jhon"), seconds() / 30), "203.0.113.5").status());
    }

    @Test
    void theTotpSecretIsEncryptedAtRestAndTamperingIsDetected() {
        auth.login("jhon", PASSWORD, null, "203.0.113.5");
        String stored = jdbc.queryForObject("SELECT totp_secret_enc FROM admin_accounts", String.class);
        String secret = auth.currentSecretForTests("jhon");
        assertFalse(stored.contains(secret));
        var vault = new TotpSecretVault(new byte[32]);
        assertArrayEqualsLength(vault.decrypt(stored));
        assertThrows(IllegalStateException.class, () -> new TotpSecretVault(new byte[] {1, 2, 3}));
        assertThrows(IllegalStateException.class, () -> vault.decrypt(stored.substring(0, stored.length() - 4) + "AAAA"));
        assertThrows(IllegalStateException.class, () -> new TotpSecretVault(java.util.Arrays.copyOf(new byte[] {9}, 32)).decrypt(stored));
    }

    private static void assertArrayEqualsLength(byte[] decrypted) { assertEquals(20, decrypted.length); assertNotNull(decrypted); }
}
