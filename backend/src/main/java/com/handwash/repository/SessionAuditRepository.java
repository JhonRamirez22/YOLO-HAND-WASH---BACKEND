package com.handwash.repository;

import com.handwash.model.EncounterAuditRecord;
import com.handwash.model.SessionAuditEvent;
import com.handwash.service.audit.AuditChainHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SessionAuditRepository {
    private final JdbcTemplate jdbcTemplate;
    private final AuditChainHasher hasher;
    private final TransactionTemplate transaction;

    public SessionAuditRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AuditChainHasher(null));
    }

    @Autowired
    public SessionAuditRepository(JdbcTemplate jdbcTemplate, AuditChainHasher hasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.hasher = hasher;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbcTemplate.getDataSource())));
    }

    public void createEncounter(EncounterAuditRecord encounter) {
        jdbcTemplate.update("""
            INSERT INTO wash_encounters
                (session_id, protocol_code, status, started_at_epoch_ms, ended_at_epoch_ms,
                 model_version, model_sha256, dataset_version, attempts_restarted,
                 producer_client_id, producer_epoch, protocol_profile_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            encounter.sessionId(), encounter.protocolCode(), encounter.status(),
            encounter.startedAtEpochMs(), encounter.endedAtEpochMs(), encounter.modelVersion(),
            encounter.modelSha256(), encounter.datasetVersion(), encounter.attemptsRestarted(),
            encounter.producerClientId(), encounter.producerEpoch(),
            encounter.protocolProfileVersion());
    }

    /**
     * Appends the event and links it to the chain. The head row is locked for the whole append, so
     * concurrent writers produce one consistent order (joins the caller's transaction when present).
     */
    public void appendEvent(SessionAuditEvent event) {
        transaction.executeWithoutResult(status -> {
            String previous = jdbcTemplate.queryForObject(
                "SELECT last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE", String.class);
            String hash = hasher.linkHash(previous, event);
            KeyHolder keys = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO wash_audit_events
                        (session_id, event_type, actor_subject, step_code, decision_code, reason_code,
                         attempt_number, frame_sequence, confidence, occurred_at_epoch_ms, prev_hash, event_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"event_id"});
                statement.setObject(1, event.sessionId());
                statement.setObject(2, event.eventType());
                statement.setObject(3, event.actorSubject());
                statement.setObject(4, event.stepCode());
                statement.setObject(5, event.decisionCode());
                statement.setObject(6, event.reasonCode());
                statement.setObject(7, event.attemptNumber());
                statement.setObject(8, event.frameSequence());
                statement.setObject(9, event.confidence());
                statement.setLong(10, event.occurredAtEpochMs());
                statement.setString(11, previous);
                statement.setString(12, hash);
                return statement;
            }, keys);
            Number id = keys.getKey();
            jdbcTemplate.update("UPDATE audit_chain_head SET last_event_id = ?, last_hash = ? WHERE id = 1",
                id == null ? null : id.longValue(), hash);
        });
    }

    public int updateEncounter(String sessionId, String status, Long endedAtEpochMs,
                               int attemptsRestarted) {
        int updated = jdbcTemplate.update("""
            UPDATE wash_encounters
            SET status = ?,
                ended_at_epoch_ms = COALESCE(ended_at_epoch_ms, ?),
                attempts_restarted = ?
            WHERE session_id = ?
            """, status, endedAtEpochMs, attemptsRestarted, sessionId);
        if (updated == 1 && List.of("COMPLETED", "INTERRUPTED", "DELETED").contains(status)) {
            releaseStationSession(sessionId);
        }
        return updated;
    }

    public int registerProducer(String sessionId, String producerClientId, String producerEpoch) {
        return jdbcTemplate.update("""
            UPDATE wash_encounters
            SET producer_client_id = ?, producer_epoch = ?
            WHERE session_id = ? AND status IN ('ACTIVE', 'IN_PROGRESS')
            """, producerClientId, producerEpoch, sessionId);
    }

    public List<EncounterAuditRecord> findRecentEncounters(int limit) {
        return jdbcTemplate.query("""
            SELECT session_id, protocol_code, status, started_at_epoch_ms, ended_at_epoch_ms,
                   model_version, model_sha256, dataset_version, attempts_restarted,
                   producer_client_id, producer_epoch, protocol_profile_version
            FROM wash_encounters
            ORDER BY started_at_epoch_ms DESC, session_id DESC
            LIMIT ?
            """, (rs, row) -> new EncounterAuditRecord(
                rs.getString("session_id"), rs.getString("protocol_code"), rs.getString("status"),
                rs.getLong("started_at_epoch_ms"), rs.getObject("ended_at_epoch_ms", Long.class),
                rs.getString("model_version"), rs.getString("model_sha256"),
                rs.getString("dataset_version"), rs.getInt("attempts_restarted"),
                rs.getString("producer_client_id"), rs.getString("producer_epoch"),
                rs.getObject("protocol_profile_version", Integer.class)), limit);
    }

    public List<SessionAuditEvent> findEvents(String sessionId, Long beforeEventId, int limit) {
        String query = beforeEventId == null ? """
            SELECT event_id, session_id, event_type, actor_subject, step_code, decision_code, reason_code,
                   attempt_number, frame_sequence, confidence, occurred_at_epoch_ms
            FROM wash_audit_events
            WHERE session_id = ?
            ORDER BY event_id DESC
            LIMIT ?
        """ : """
            SELECT event_id, session_id, event_type, actor_subject, step_code, decision_code, reason_code,
                   attempt_number, frame_sequence, confidence, occurred_at_epoch_ms
            FROM wash_audit_events
            WHERE session_id = ? AND event_id < ?
            ORDER BY event_id DESC
            LIMIT ?
            """;
        if (beforeEventId == null) {
            return jdbcTemplate.query(query, (rs, row) -> mapEvent(rs), sessionId, limit);
        }
        return jdbcTemplate.query(query, (rs, row) -> mapEvent(rs),
            sessionId, beforeEventId, limit);
    }

    public boolean encounterExists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wash_encounters WHERE session_id = ?", Integer.class, sessionId);
        return count != null && count == 1;
    }

    public List<String> findNonTerminalEncounterIds() {
        return jdbcTemplate.queryForList("""
            SELECT session_id FROM wash_encounters
            WHERE status IN ('ACTIVE', 'IN_PROGRESS')
            ORDER BY started_at_epoch_ms, session_id
            """, String.class);
    }

    public int interruptEncounterAfterRuntimeRestart(String sessionId, long endedAtEpochMs) {
        int updated = jdbcTemplate.update("""
            UPDATE wash_encounters
            SET status = 'INTERRUPTED', ended_at_epoch_ms = ?
            WHERE session_id = ? AND status IN ('ACTIVE', 'IN_PROGRESS')
            """, endedAtEpochMs, sessionId);
        if (updated == 1) releaseStationSession(sessionId);
        return updated;
    }

    public record RehearsalRow(String step, long confirmed, long corrected) {}

    /** Operator confirmations and corrections per step (rehearsal mode); counts only. */
    public List<RehearsalRow> rehearsalSummary() {
        return jdbcTemplate.query("""
            SELECT step_code,
                   SUM(CASE WHEN decision_code = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmed,
                   SUM(CASE WHEN decision_code = 'CORRECTED' THEN 1 ELSE 0 END) AS corrected
            FROM wash_audit_events
            WHERE event_type = 'OPERATOR_STEP_FEEDBACK' AND step_code IS NOT NULL
            GROUP BY step_code
            ORDER BY step_code
            """, (rs, row) -> new RehearsalRow(rs.getString("step_code"), rs.getLong("confirmed"),
            rs.getLong("corrected")));
    }

    public List<SessionAuditEvent> findAdminAccessEvents(int limit) {
        return jdbcTemplate.query("""
            SELECT event_id, session_id, event_type, actor_subject, step_code, decision_code,
                   reason_code, attempt_number, frame_sequence, confidence, occurred_at_epoch_ms
            FROM wash_audit_events
            WHERE event_type IN ('ADMIN_ENCOUNTERS_LISTED', 'ADMIN_EVENTS_READ',
                                 'ADMIN_ACCESS_LOG_READ', 'ADMIN_FAILED_ATTEMPTS_READ',
                                 'ADMIN_STATIONS_LISTED', 'ADMIN_OPERATORS_LISTED',
                                 'ADMIN_OPERATOR_CODES_LISTED',
                                 'STATION_ENROLLMENT_CODE_ISSUED', 'STATION_ENROLLED',
                                 'STATION_REVOKED', 'STATION_PRODUCER_BOUND', 'STATION_PROVISIONED',
                                 'STATION_ACTIVATED', 'STATION_ACTIVATION_REISSUED',
                                 'ADMIN_ACCOUNT_BOOTSTRAPPED', 'ADMIN_LOGIN', 'ADMIN_LOGIN_LOCKED', 'ADMIN_PASSWORD_CHANGED', 'STATION_LINKED_BY_KEY', 'API_KEY_ISSUED', 'API_KEY_REVOKED', 'API_KEY_LOGIN', 'OPERATOR_ACCOUNT_CREATED', 'OPERATOR_ACCOUNT_DISABLE_FAILED',
                                 'STATION_SESSION_STARTED', 'OPERATOR_ACCESS_CODE_ISSUED',
                                 'OPERATOR_ACCESS_CODE_CLAIMED', 'OPERATOR_ACCESS_CODE_REVOKED',
                                 'OPERATOR_GRANT_REVOKED', 'OPERATOR_SESSION_STARTED',
                                 'OPERATOR_SESSION_STOPPED', 'OPERATOR_SESSION_TICKET_ISSUED')
            ORDER BY event_id DESC
            LIMIT ?
            """, (rs, row) -> mapEvent(rs), limit);
    }

    private SessionAuditEvent mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionAuditEvent(
            rs.getLong("event_id"), rs.getString("session_id"), rs.getString("event_type"),
            rs.getString("actor_subject"),
            rs.getString("step_code"), rs.getString("decision_code"), rs.getString("reason_code"),
            rs.getObject("attempt_number", Integer.class), rs.getObject("frame_sequence", Long.class),
            rs.getObject("confidence", Double.class), rs.getLong("occurred_at_epoch_ms"));
    }

    public int releaseStationSession(String sessionId) {
        return jdbcTemplate.update("""
            UPDATE handwash_stations
            SET active_session_id = NULL, active_request_id = NULL
            WHERE active_session_id = ?
            """, sessionId);
    }
}
