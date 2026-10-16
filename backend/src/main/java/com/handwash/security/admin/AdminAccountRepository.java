package com.handwash.security.admin;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class AdminAccountRepository {
    private static final String SELECT = """
        SELECT account_id, username, password_hash, totp_secret_enc, totp_confirmed, last_totp_step,
               password_failures, totp_failures, failure_window_start_ms, locked_until_ms, disabled
        FROM admin_accounts""";
    private final JdbcTemplate jdbc;

    public AdminAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(long id, String username, String passwordHash, String totpSecretEnc, boolean totpConfirmed,
                          long lastTotpStep, int passwordFailures, int totpFailures, Long failureWindowStartMs,
                          Long lockedUntilMs, boolean disabled) {}

    public int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM admin_accounts", Integer.class); }

    public void insert(String username, String passwordHash, String totpSecretEnc, long now) {
        jdbc.update("INSERT INTO admin_accounts (username, password_hash, totp_secret_enc, created_at_epoch_ms) VALUES (?, ?, ?, ?)",
            username, passwordHash, totpSecretEnc, now);
    }

    public Account findByUsername(String username) {
        try {
            return jdbc.queryForObject(SELECT + " WHERE username = ?", (rs, row) -> map(rs), username);
        } catch (EmptyResultDataAccessException missing) { return null; }
    }

    public void saveFailures(long id, int passwordFailures, int totpFailures, Long windowStart, Long lockedUntil) {
        jdbc.update("UPDATE admin_accounts SET password_failures = ?, totp_failures = ?, failure_window_start_ms = ?, locked_until_ms = ? WHERE account_id = ?",
            passwordFailures, totpFailures, windowStart, lockedUntil, id);
    }

    public void recordSuccess(long id, long totpStep, boolean confirm) {
        jdbc.update("""
            UPDATE admin_accounts SET last_totp_step = ?, totp_confirmed = (totp_confirmed OR ?),
                password_failures = 0, totp_failures = 0, failure_window_start_ms = NULL, locked_until_ms = NULL
            WHERE account_id = ?""", totpStep, confirm, id);
    }

    public void updatePassword(long id, String hash) {
        jdbc.update("UPDATE admin_accounts SET password_hash = ? WHERE account_id = ?", hash, id);
    }

    public Account findById(long id) {
        try {
            return jdbc.queryForObject(SELECT + " WHERE account_id = ?", (rs, row) -> map(rs), id);
        } catch (EmptyResultDataAccessException missing) { return null; }
    }

    public void insertSession(String tokenHash, long accountId, long expiresAt) {
        jdbc.update("INSERT INTO admin_sessions (token_hash, account_id, expires_at_epoch_ms) VALUES (?, ?, ?)", tokenHash, accountId, expiresAt);
    }

    /** account id of a live session, or -1. */
    public long findLiveSession(String tokenHash, long now) {
        var rows = jdbc.query("SELECT account_id FROM admin_sessions WHERE token_hash = ? AND expires_at_epoch_ms > ?",
            (rs, row) -> rs.getLong(1), tokenHash, now);
        return rows.isEmpty() ? -1 : rows.get(0);
    }

    public void deleteSession(String tokenHash) { jdbc.update("DELETE FROM admin_sessions WHERE token_hash = ?", tokenHash); }
    public void deleteSessionsOfAccount(long id) { jdbc.update("DELETE FROM admin_sessions WHERE account_id = ?", id); }
    public void purgeExpiredSessions(long now) { jdbc.update("DELETE FROM admin_sessions WHERE expires_at_epoch_ms <= ?", now); }

    private static Account map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Account(rs.getLong("account_id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("totp_secret_enc"), rs.getBoolean("totp_confirmed"), rs.getLong("last_totp_step"),
            rs.getInt("password_failures"), rs.getInt("totp_failures"), rs.getObject("failure_window_start_ms", Long.class),
            rs.getObject("locked_until_ms", Long.class), rs.getBoolean("disabled"));
    }
}
