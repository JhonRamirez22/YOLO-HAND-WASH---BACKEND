package com.handwash.service.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/** References and statuses only; the image bytes live in the encrypted object store. */
@Repository
public class WashEvidenceRepository {
    public record AttemptView(String outcome, boolean terminal, Long finishedAtEpochMs, String evidenceStatus) {}
    public record StoredEvidence(String sessionId, int attemptNumber, String objectKey) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public WashEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    public Optional<AttemptView> findAttempt(String sessionId, int attemptNumber) {
        return jdbc.query("""
            SELECT outcome, terminal, finished_at_epoch_ms, evidence_status
            FROM friction_attempt_validation WHERE session_id = ? AND attempt_number = ?""",
            (rs, row) -> new AttemptView(rs.getString("outcome"), rs.getBoolean("terminal"),
                rs.getObject("finished_at_epoch_ms", Long.class), rs.getString("evidence_status")),
            sessionId, attemptNumber).stream().findFirst();
    }

    public Optional<String> findObjectKey(String sessionId, int attemptNumber) {
        return jdbc.query("SELECT object_key FROM wash_report_evidence WHERE session_id = ? AND attempt_number = ?",
            (rs, row) -> rs.getString("object_key"), sessionId, attemptNumber).stream().findFirst();
    }

    /** Reference and status change together, or neither does. */
    public void store(String sessionId, int attemptNumber, String objectKey, String sha256,
                      long capturedAtEpochMs, long expiresAtEpochMs) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("""
                INSERT INTO wash_report_evidence
                    (session_id, attempt_number, object_key, sha256, captured_at_epoch_ms, expires_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?)""", sessionId, attemptNumber, objectKey, sha256,
                capturedAtEpochMs, expiresAtEpochMs);
            jdbc.update("UPDATE friction_attempt_validation SET evidence_status = 'CAPTURADA' WHERE session_id = ? AND attempt_number = ?",
                sessionId, attemptNumber);
        });
    }

    public List<StoredEvidence> findExpired(long nowEpochMs, int limit) {
        return jdbc.query("""
            SELECT session_id, attempt_number, object_key FROM wash_report_evidence
            WHERE expires_at_epoch_ms <= ? ORDER BY expires_at_epoch_ms LIMIT ?""",
            (rs, row) -> new StoredEvidence(rs.getString("session_id"), rs.getInt("attempt_number"),
                rs.getString("object_key")), nowEpochMs, limit);
    }

    /** Called after the object is gone: the row is deleted and the attempt remembers it expired. */
    public void markExpired(String sessionId, int attemptNumber) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM wash_report_evidence WHERE session_id = ? AND attempt_number = ?", sessionId, attemptNumber);
            jdbc.update("UPDATE friction_attempt_validation SET evidence_status = 'EXPIRADA' WHERE session_id = ? AND attempt_number = ?",
                sessionId, attemptNumber);
        });
    }

    /** Pending uploads whose window has closed can no longer be satisfied. */
    public int markMissed(long finishedBeforeEpochMs) {
        return jdbc.update("""
            UPDATE friction_attempt_validation SET evidence_status = 'NO_CAPTURADA'
            WHERE evidence_status = 'PENDIENTE' AND finished_at_epoch_ms < ?""", finishedBeforeEpochMs);
    }
}
