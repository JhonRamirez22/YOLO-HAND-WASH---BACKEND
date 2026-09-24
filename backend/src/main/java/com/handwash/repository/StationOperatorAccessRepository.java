package com.handwash.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class StationOperatorAccessRepository {
    private final JdbcTemplate jdbc;

    public StationOperatorAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createCode(OperatorAccessCode code) {
        jdbc.update("""
            INSERT INTO station_operator_access_codes
                (code_id, code_hash, station_id, target_email, created_by, created_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """, code.codeId(), code.codeHash(), code.stationId(), code.email(),
            code.createdBy(), code.createdAtEpochMs());
    }

    public OperatorAccessCode findClaimableCodeForUpdate(String codeHash) {
        try {
            return jdbc.queryForObject("""
                SELECT code_id, code_hash, station_id, target_email, created_by, created_at_epoch_ms,
                       consumed_at_epoch_ms, consumed_by, revoked_at_epoch_ms
                FROM station_operator_access_codes
                WHERE code_hash = ? AND consumed_at_epoch_ms IS NULL AND revoked_at_epoch_ms IS NULL
                FOR UPDATE
                """, (rs, row) -> mapCode(rs), codeHash);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    public int consumeCode(String codeId, String subject, long now) {
        return jdbc.update("""
            UPDATE station_operator_access_codes
            SET consumed_at_epoch_ms = ?, consumed_by = ?
            WHERE code_id = ? AND consumed_at_epoch_ms IS NULL AND revoked_at_epoch_ms IS NULL
            """, now, subject, codeId);
    }

    public void grant(String subject, String email, String stationId, String grantedBy, long now) {
        List<OperatorGrant> existing = jdbc.query("""
            SELECT cognito_subject, email, station_id, granted_by, granted_at_epoch_ms,
                   revoked_at_epoch_ms
            FROM station_operator_grants WHERE cognito_subject = ? FOR UPDATE
            """, (rs, row) -> mapGrant(rs), subject);
        if (existing.isEmpty()) {
            try {
                jdbc.update("""
                    INSERT INTO station_operator_grants
                        (cognito_subject, email, station_id, granted_by, granted_at_epoch_ms)
                    VALUES (?, ?, ?, ?, ?)
                    """, subject, email, stationId, grantedBy, now);
            } catch (org.springframework.dao.DuplicateKeyException concurrentAssignment) {
                throw new OperatorAlreadyAssignedException();
            }
            return;
        }
        OperatorGrant prior = existing.get(0);
        if (prior.revokedAtEpochMs() == null) {
            throw new OperatorAlreadyAssignedException();
        }
        jdbc.update("""
            UPDATE station_operator_grants
            SET email = ?, station_id = ?, granted_by = ?, granted_at_epoch_ms = ?,
                revoked_at_epoch_ms = NULL
            WHERE cognito_subject = ? AND revoked_at_epoch_ms IS NOT NULL
            """, email, stationId, grantedBy, now, subject);
    }

    public OperatorGrant findActiveGrant(String subject) {
        try {
            return jdbc.queryForObject("""
                SELECT cognito_subject, email, station_id, granted_by, granted_at_epoch_ms,
                       revoked_at_epoch_ms
                FROM station_operator_grants
                WHERE cognito_subject = ? AND revoked_at_epoch_ms IS NULL
                """, (rs, row) -> mapGrant(rs), subject);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    public OperatorGrant findActiveGrantForUpdate(String subject) {
        try {
            return jdbc.queryForObject("""
                SELECT cognito_subject, email, station_id, granted_by, granted_at_epoch_ms,
                       revoked_at_epoch_ms
                FROM station_operator_grants
                WHERE cognito_subject = ? AND revoked_at_epoch_ms IS NULL
                FOR UPDATE
                """, (rs, row) -> mapGrant(rs), subject);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    public List<OperatorGrant> listGrants() {
        return jdbc.query("""
            SELECT cognito_subject, email, station_id, granted_by, granted_at_epoch_ms,
                   revoked_at_epoch_ms
            FROM station_operator_grants
            ORDER BY granted_at_epoch_ms DESC, cognito_subject
            LIMIT 500
            """, (rs, row) -> mapGrant(rs));
    }

    public List<OperatorAccessCode> listCodes() {
        return jdbc.query("""
            SELECT code_id, code_hash, station_id, target_email, created_by, created_at_epoch_ms,
                   consumed_at_epoch_ms, consumed_by, revoked_at_epoch_ms
            FROM station_operator_access_codes
            ORDER BY created_at_epoch_ms DESC, code_id
            LIMIT 500
            """, (rs, row) -> mapCode(rs));
    }

    public int revokeCode(String codeId, long now) {
        return jdbc.update("""
            UPDATE station_operator_access_codes SET revoked_at_epoch_ms = ?
            WHERE code_id = ? AND consumed_at_epoch_ms IS NULL AND revoked_at_epoch_ms IS NULL
            """, now, codeId);
    }

    public int revokeGrant(String subject, long now) {
        return jdbc.update("""
            UPDATE station_operator_grants SET revoked_at_epoch_ms = ?
            WHERE cognito_subject = ? AND revoked_at_epoch_ms IS NULL
            """, now, subject);
    }

    public record OperatorAccessCode(String codeId, String codeHash, String stationId, String email,
                                    String createdBy, long createdAtEpochMs,
                                    Long consumedAtEpochMs, String consumedBy,
                                    Long revokedAtEpochMs) {}

    public record OperatorGrant(String subject, String email, String stationId, String grantedBy,
                                long grantedAtEpochMs, Long revokedAtEpochMs) {}

    public static final class OperatorAlreadyAssignedException extends RuntimeException {}

    private static OperatorAccessCode mapCode(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OperatorAccessCode(rs.getString("code_id"), rs.getString("code_hash"),
            rs.getString("station_id"), rs.getString("target_email"), rs.getString("created_by"),
            rs.getLong("created_at_epoch_ms"), rs.getObject("consumed_at_epoch_ms", Long.class),
            rs.getString("consumed_by"), rs.getObject("revoked_at_epoch_ms", Long.class));
    }

    private static OperatorGrant mapGrant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OperatorGrant(rs.getString("cognito_subject"), rs.getString("email"),
            rs.getString("station_id"), rs.getString("granted_by"),
            rs.getLong("granted_at_epoch_ms"), rs.getObject("revoked_at_epoch_ms", Long.class));
    }
}
