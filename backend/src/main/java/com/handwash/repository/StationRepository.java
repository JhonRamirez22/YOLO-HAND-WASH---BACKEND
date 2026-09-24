package com.handwash.repository;

import com.handwash.model.StationRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class StationRepository {
    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createEnrollmentCode(String codeHash, String stationName, String actor,
                                     long createdAt, long expiresAt) {
        jdbc.update("""
            INSERT INTO station_enrollment_codes
                (code_hash, station_name, created_by, created_at_epoch_ms, expires_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?)
            """, codeHash, stationName, actor, createdAt, expiresAt);
    }

    /** Must be called inside a transaction: row locking makes enrollment single-use under races. */
    public EnrollmentCode consumeEnrollmentCode(String codeHash, long now) {
        List<EnrollmentCode> matches = jdbc.query("""
            SELECT station_name, created_by
            FROM station_enrollment_codes
            WHERE code_hash = ? AND consumed_at_epoch_ms IS NULL AND expires_at_epoch_ms > ?
            FOR UPDATE
            """, (rs, row) -> new EnrollmentCode(rs.getString("station_name"),
                rs.getString("created_by")), codeHash, now);
        if (matches.isEmpty()) return null;
        int updated = jdbc.update("""
            UPDATE station_enrollment_codes
            SET consumed_at_epoch_ms = ?
            WHERE code_hash = ? AND consumed_at_epoch_ms IS NULL AND expires_at_epoch_ms > ?
            """, now, codeHash, now);
        return updated == 1 ? matches.get(0) : null;
    }

    public void createStation(StationRecord station, String credentialHash) {
        jdbc.update("""
            INSERT INTO handwash_stations
                (station_id, station_name, credential_hash, created_at_epoch_ms, created_by, activated_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """, station.stationId(), station.stationName(), credentialHash,
            station.createdAtEpochMs(), station.createdBy(), station.createdAtEpochMs());
    }

    /** A station created by the ADMIN: its device credential is unusable until the Mac redeems the token. */
    public void createPendingStation(StationRecord station, String placeholderCredentialHash,
                                     String activationHash, long activationExpiresAt) {
        jdbc.update("""
            INSERT INTO handwash_stations
                (station_id, station_name, credential_hash, created_at_epoch_ms, created_by,
                 producer_client_id, activation_hash, activation_expires_at_epoch_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, station.stationId(), station.stationName(), placeholderCredentialHash,
            station.createdAtEpochMs(), station.createdBy(), station.producerClientId(),
            activationHash, activationExpiresAt);
    }

    /** Must run in a transaction: the row lock makes the activation token single-use under races. */
    public StationRecord activate(String activationHash, String credentialHash, long now) {
        List<StationRecord> matches = jdbc.query("""
            SELECT station_id, station_name, created_at_epoch_ms, created_by,
                   revoked_at_epoch_ms, last_started_at_epoch_ms, active_session_id,
                   active_request_id, producer_client_id
            FROM handwash_stations
            WHERE activation_hash = ? AND activated_at_epoch_ms IS NULL
              AND revoked_at_epoch_ms IS NULL AND activation_expires_at_epoch_ms > ?
            FOR UPDATE
            """, StationRepository::mapStation, activationHash, now);
        if (matches.isEmpty()) return null;
        int updated = jdbc.update("""
            UPDATE handwash_stations
            SET credential_hash = ?, activated_at_epoch_ms = ?, activation_hash = NULL
            WHERE station_id = ? AND activated_at_epoch_ms IS NULL
            """, credentialHash, now, matches.get(0).stationId());
        return updated == 1 ? matches.get(0) : null;
    }

    /** Replaces the activation token of a station whose Mac has not activated yet. */
    public int reissueActivation(String stationId, String activationHash, long expiresAt) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET activation_hash = ?, activation_expires_at_epoch_ms = ?
            WHERE station_id = ? AND activated_at_epoch_ms IS NULL AND revoked_at_epoch_ms IS NULL
              AND credential_hash IS NOT NULL
            """, activationHash, expiresAt, stationId);
    }

    public java.util.Map<String, Long> activationTimes() {
        java.util.Map<String, Long> times = new java.util.HashMap<>();
        jdbc.query("SELECT station_id, activated_at_epoch_ms FROM handwash_stations",
            rs -> { times.put(rs.getString(1), rs.getObject(2, Long.class)); });
        return times;
    }

    public Long activatedAt(String stationId) {
        List<Long> rows = jdbc.query("SELECT activated_at_epoch_ms FROM handwash_stations WHERE station_id = ?",
            (rs, row) -> rs.getObject(1, Long.class), stationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StationRecord findActiveByCredentialHash(String credentialHash) {
        try {
            return jdbc.queryForObject("""
                SELECT station_id, station_name, created_at_epoch_ms, created_by,
                       revoked_at_epoch_ms, last_started_at_epoch_ms, active_session_id,
                       active_request_id, producer_client_id
                FROM handwash_stations
                WHERE credential_hash = ? AND revoked_at_epoch_ms IS NULL
                """, StationRepository::mapStation, credentialHash);
        } catch (EmptyResultDataAccessException notFound) {
            return null;
        }
    }

    public StationRecord findActiveByProducerClient(String producerClientId) {
        try {
            return jdbc.queryForObject("""
                SELECT station_id, station_name, created_at_epoch_ms, created_by,
                       revoked_at_epoch_ms, last_started_at_epoch_ms, active_session_id,
                       active_request_id, producer_client_id
                FROM handwash_stations
                WHERE producer_client_id = ? AND revoked_at_epoch_ms IS NULL
                """, StationRepository::mapStation, producerClientId);
        } catch (EmptyResultDataAccessException notFound) {
            return null;
        }
    }

    public StationRecord findActiveById(String stationId) {
        try {
            return jdbc.queryForObject("""
                SELECT station_id, station_name, created_at_epoch_ms, created_by,
                       revoked_at_epoch_ms, last_started_at_epoch_ms, active_session_id,
                       active_request_id, producer_client_id
                FROM handwash_stations
                WHERE station_id = ? AND revoked_at_epoch_ms IS NULL
                """, StationRepository::mapStation, stationId);
        } catch (EmptyResultDataAccessException notFound) {
            return null;
        }
    }

    public List<StationRecord> findAll() {
        return jdbc.query("""
            SELECT station_id, station_name, created_at_epoch_ms, created_by,
                   revoked_at_epoch_ms, last_started_at_epoch_ms, active_session_id,
                   active_request_id, producer_client_id
            FROM handwash_stations
            ORDER BY created_at_epoch_ms DESC, station_id
            """, StationRepository::mapStation);
    }

    public int revoke(String stationId, long now) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET revoked_at_epoch_ms = ?
            WHERE station_id = ? AND revoked_at_epoch_ms IS NULL
            """, now, stationId);
    }

    public int bindProducerClient(String stationId, String clientId) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET producer_client_id = ?
            WHERE station_id = ? AND revoked_at_epoch_ms IS NULL
            """, clientId, stationId);
    }

    /** Clears the PC binding only if it still points at that client, so a newer PC key is never undone. */
    public int clearProducerClient(String stationId, String clientId) {
        return jdbc.update("""
            UPDATE handwash_stations SET producer_client_id = NULL
            WHERE station_id = ? AND producer_client_id = ?
            """, stationId, clientId);
    }

    /** Replaces the Mac's credential. Without {@code force} it only succeeds while no Mac is linked yet. */
    public int replaceCredential(String stationId, String credentialHash, long now, boolean force) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET credential_hash = ?, activated_at_epoch_ms = COALESCE(activated_at_epoch_ms, ?)
            WHERE station_id = ? AND revoked_at_epoch_ms IS NULL AND (? OR activated_at_epoch_ms IS NULL)
            """, credentialHash, now, stationId, force);
    }

    public int reserveSession(String stationId, String sessionId, String requestId, long now) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET active_session_id = ?, active_request_id = ?, last_started_at_epoch_ms = ?
            WHERE station_id = ? AND revoked_at_epoch_ms IS NULL AND active_session_id IS NULL
            """, sessionId, requestId, now, stationId);
    }

    public int releaseSession(String sessionId) {
        return jdbc.update("""
            UPDATE handwash_stations
            SET active_session_id = NULL, active_request_id = NULL
            WHERE active_session_id = ?
            """, sessionId);
    }

    private static StationRecord mapStation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StationRecord(rs.getString("station_id"), rs.getString("station_name"),
            rs.getLong("created_at_epoch_ms"), rs.getString("created_by"),
            rs.getObject("revoked_at_epoch_ms", Long.class),
            rs.getObject("last_started_at_epoch_ms", Long.class),
            rs.getString("active_session_id"), rs.getString("active_request_id"),
            rs.getString("producer_client_id"));
    }

    public record EnrollmentCode(String stationName, String createdBy) {}
}
