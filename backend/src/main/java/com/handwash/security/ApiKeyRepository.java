package com.handwash.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class ApiKeyRepository {
    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(StoredKey key) {
        jdbc.update("""
            INSERT INTO api_keys (key_id, station_id, label, secret_hash, scopes, created_by, created_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, key.keyId(), key.stationId(), key.label(), key.secretHash(), encode(key.scopes()),
            key.createdBy(), key.createdAtEpochMs());
    }

    public StoredKey find(String keyId) {
        try {
            return jdbc.queryForObject(SELECT + " WHERE key_id = ?", (rs, row) -> map(rs), keyId);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    public List<StoredKey> findAll() {
        return jdbc.query(SELECT + " ORDER BY created_at_epoch_ms DESC, key_id", (rs, row) -> map(rs));
    }

    public int markUsed(String keyId, long now, String ip) {
        return jdbc.update("UPDATE api_keys SET last_used_at_epoch_ms = ?, last_used_ip = ? WHERE key_id = ?",
            now, ip, keyId);
    }

    public int revoke(String keyId, long now, String admin) {
        return jdbc.update("""
            UPDATE api_keys SET revoked_at_epoch_ms = ?, revoked_by = ?
            WHERE key_id = ? AND revoked_at_epoch_ms IS NULL
            """, now, admin, keyId);
    }

    public void insertSession(String tokenHash, String keyId, long expiresAt) {
        jdbc.update("INSERT INTO api_key_sessions (token_hash, key_id, expires_at_epoch_ms) VALUES (?, ?, ?)",
            tokenHash, keyId, expiresAt);
    }

    /** Key id of a live session, or null when the token is unknown or past its expiry. */
    public String findLiveSessionKey(String tokenHash, long now) {
        List<String> rows = jdbc.query("SELECT key_id FROM api_key_sessions WHERE token_hash = ? AND expires_at_epoch_ms > ?",
            (rs, row) -> rs.getString(1), tokenHash, now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int deleteSessionsOfKey(String keyId) {
        return jdbc.update("DELETE FROM api_key_sessions WHERE key_id = ?", keyId);
    }

    public int purgeExpiredSessions(long now) {
        return jdbc.update("DELETE FROM api_key_sessions WHERE expires_at_epoch_ms <= ?", now);
    }

    private static final String SELECT = """
        SELECT key_id, station_id, label, secret_hash, scopes, created_by, created_at_epoch_ms,
               last_used_at_epoch_ms, last_used_ip, revoked_at_epoch_ms, revoked_by
        FROM api_keys""";

    private static StoredKey map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredKey(rs.getString("key_id"), rs.getString("station_id"), rs.getString("label"),
            rs.getString("secret_hash"), decode(rs.getString("scopes")), rs.getString("created_by"),
            rs.getLong("created_at_epoch_ms"), rs.getObject("last_used_at_epoch_ms", Long.class),
            rs.getString("last_used_ip"), rs.getObject("revoked_at_epoch_ms", Long.class),
            rs.getString("revoked_by"));
    }

    static String encode(Set<ApiKeyScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Set<ApiKeyScope> decode(String value) {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        for (String part : value.split(",")) if (!part.isBlank()) scopes.add(ApiKeyScope.valueOf(part));
        return scopes;
    }

    public record StoredKey(String keyId, String stationId, String label, String secretHash,
                            Set<ApiKeyScope> scopes, String createdBy, long createdAtEpochMs,
                            Long lastUsedAtEpochMs, String lastUsedIp, Long revokedAtEpochMs, String revokedBy) {}
}
