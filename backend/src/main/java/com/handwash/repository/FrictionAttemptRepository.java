package com.handwash.repository;

import com.handwash.model.FrictionAttemptRecord;
import com.handwash.model.FrictionEvaluation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** One atomic, idempotent write per significant decision; never stores frames. */
@Repository
public class FrictionAttemptRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public FrictionAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(
            java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    public void save(FrictionAttemptRecord record) {
        transaction.executeWithoutResult(tx -> {
            long now = System.currentTimeMillis();
            var existing = jdbc.queryForList("SELECT revision, terminal FROM friction_attempt_validation WHERE session_id=? AND attempt_number=? FOR UPDATE",
                record.sessionId(), record.attemptNumber());
            if (!existing.isEmpty()) {
                long revision = ((Number) existing.get(0).get("revision")).longValue();
                if (revision >= record.revision()) return;
                if (Boolean.TRUE.equals(existing.get(0).get("terminal"))) {
                    throw new IllegalStateException("A terminal friction attempt is immutable");
                }
                jdbc.update("""
                    UPDATE friction_attempt_validation SET revision=?, status=?, result=?, reason=?, terminal=?,
                        updated_at_epoch_ms=?, started_at_epoch_ms=COALESCE(started_at_epoch_ms, ?),
                        finished_at_epoch_ms=?, duration_ms=?, slow=?, outcome=?, evidence_status=?, score_pct=?, verdict=?
                    WHERE session_id=? AND attempt_number=?""",
                    record.revision(), record.status(), record.result(), record.reason(), record.terminal(), now,
                    record.startedAtEpochMs(), record.finishedAtEpochMs(), record.durationMs(), record.slow(),
                    outcome(record), record.evidenceStatus(), record.scorePct(), record.verdict(), record.sessionId(), record.attemptNumber());
            } else {
                jdbc.update("""
                    INSERT INTO friction_attempt_validation (session_id, attempt_number, policy_version, revision,
                        status, result, reason, terminal, created_at_epoch_ms, updated_at_epoch_ms,
                        started_at_epoch_ms, finished_at_epoch_ms, duration_ms, slow, outcome, evidence_status, score_pct, verdict)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    record.sessionId(), record.attemptNumber(), record.policyVersion(), record.revision(),
                    record.status(), record.result(), record.reason(), record.terminal(), now, now,
                    record.startedAtEpochMs(), record.finishedAtEpochMs(), record.durationMs(), record.slow(),
                    outcome(record), record.evidenceStatus(), record.scorePct(), record.verdict());
            }
            for (var step : record.steps()) {
                int changed = jdbc.update("UPDATE friction_step_validation SET status=?, credited_ms=?, required_ms=?, reason=?, quality_pct=? WHERE session_id=? AND attempt_number=? AND step_code=?",
                    step.estado().name(), step.tiempoAcreditadoMs(), step.minimoRequeridoMs(), step.motivo(), step.calidadPct(), record.sessionId(), record.attemptNumber(), step.paso());
                if (changed == 0) jdbc.update("INSERT INTO friction_step_validation (session_id, attempt_number, step_code, status, credited_ms, required_ms, reason, quality_pct) VALUES (?,?,?,?,?,?,?,?)",
                    record.sessionId(), record.attemptNumber(), step.paso(), step.estado().name(), step.tiempoAcreditadoMs(), step.minimoRequeridoMs(), step.motivo(), step.calidadPct());
            }
        });
    }

    public int interruptAfterRestart() {
        long now = System.currentTimeMillis();
        return jdbc.update("""
            UPDATE friction_attempt_validation SET status='INTERRUMPIDO', result='INTERRUPTED',
                reason='BACKEND_RESTART', terminal=TRUE, revision=revision+1, updated_at_epoch_ms=?,
                outcome='INTERRUMPIDO', finished_at_epoch_ms=COALESCE(finished_at_epoch_ms, ?)
            WHERE terminal=FALSE""", now, now);
    }

    /** Finished attempts, newest first; {@code outcome} null means every classification. */
    public java.util.List<com.handwash.model.WashReportRecord> findReports(
        long fromEpochMs, long toEpochMs, com.handwash.model.WashOutcome outcome, int limit) {
        Object[] args = outcome == null
            ? new Object[] {fromEpochMs, toEpochMs, limit}
            : new Object[] {fromEpochMs, toEpochMs, outcome.name(), limit};
        // Each fragment ends in a newline: PostgreSQL rejects "?ORDER", which H2 accepts.
        String sql = """
            SELECT session_id, attempt_number, policy_version, outcome, reason, started_at_epoch_ms,
                   finished_at_epoch_ms, duration_ms, slow, evidence_status
            FROM friction_attempt_validation
            WHERE terminal = TRUE AND outcome IS NOT NULL
              AND finished_at_epoch_ms >= ? AND finished_at_epoch_ms < ?
            """ + (outcome == null ? "" : "  AND outcome = ?\n") + """
            ORDER BY finished_at_epoch_ms DESC, session_id, attempt_number
            LIMIT ?
            """;
        var attempts = jdbc.queryForList(sql, args);
        java.util.List<com.handwash.model.WashReportRecord> reports = new java.util.ArrayList<>(attempts.size());
        for (var row : attempts) {
            String sessionId = (String) row.get("session_id");
            int attempt = ((Number) row.get("attempt_number")).intValue();
            var steps = jdbc.query("""
                SELECT step_code, status, credited_ms, required_ms, reason FROM friction_step_validation
                WHERE session_id = ? AND attempt_number = ? ORDER BY step_code""",
                (rs, n) -> new FrictionEvaluation.Step(rs.getString("step_code"),
                    FrictionEvaluation.StepStatus.valueOf(rs.getString("status")), rs.getLong("credited_ms"),
                    rs.getLong("required_ms"), rs.getString("reason")), sessionId, attempt);
            reports.add(new com.handwash.model.WashReportRecord(sessionId, attempt,
                (String) row.get("policy_version"),
                com.handwash.model.WashOutcome.valueOf((String) row.get("outcome")),
                (String) row.get("reason"), toLong(row.get("started_at_epoch_ms")),
                toLong(row.get("finished_at_epoch_ms")), toLong(row.get("duration_ms")),
                Boolean.TRUE.equals(row.get("slow")), (String) row.get("evidence_status"), steps));
        }
        return reports;
    }

    /** Anonymous rows for statistics and CSV; at most {@code limit} attempts, oldest first. */
    public java.util.List<com.handwash.model.WashExportRow> findExportRows(
        long fromEpochMs, long toEpochMs, com.handwash.model.WashOutcome outcome, int limit) {
        Object[] args = outcome == null
            ? new Object[] {fromEpochMs, toEpochMs, limit}
            : new Object[] {fromEpochMs, toEpochMs, outcome.name(), limit};
        String sql = """
            SELECT session_id, attempt_number, outcome, reason, started_at_epoch_ms, finished_at_epoch_ms, duration_ms, slow
            FROM friction_attempt_validation
            WHERE terminal = TRUE AND outcome IS NOT NULL
              AND finished_at_epoch_ms >= ? AND finished_at_epoch_ms < ?
            """ + (outcome == null ? "" : "  AND outcome = ?\n") + """
            ORDER BY finished_at_epoch_ms, session_id, attempt_number
            LIMIT ?
            """;
        var attempts = jdbc.queryForList(sql, args);
        if (attempts.isEmpty()) return java.util.List.of();
        java.util.Map<String, java.util.Map<String, String>> stepsByAttempt = new java.util.HashMap<>();
        jdbc.query("""
            SELECT s.session_id, s.attempt_number, s.step_code, s.status
            FROM friction_step_validation s
            JOIN friction_attempt_validation a ON a.session_id = s.session_id AND a.attempt_number = s.attempt_number
            WHERE a.terminal = TRUE AND a.outcome IS NOT NULL
              AND a.finished_at_epoch_ms >= ? AND a.finished_at_epoch_ms < ?
            """, rs -> {
            stepsByAttempt.computeIfAbsent(rs.getString(1) + "#" + rs.getInt(2), k -> new java.util.LinkedHashMap<>())
                .put(rs.getString(3), rs.getString(4));
        }, fromEpochMs, toEpochMs);
        java.util.List<com.handwash.model.WashExportRow> rows = new java.util.ArrayList<>(attempts.size());
        for (var row : attempts) {
            String key = row.get("session_id") + "#" + ((Number) row.get("attempt_number")).intValue();
            rows.add(new com.handwash.model.WashExportRow(
                com.handwash.model.WashOutcome.valueOf((String) row.get("outcome")), (String) row.get("reason"),
                toLong(row.get("started_at_epoch_ms")), toLong(row.get("finished_at_epoch_ms")),
                toLong(row.get("duration_ms")), Boolean.TRUE.equals(row.get("slow")),
                stepsByAttempt.getOrDefault(key, java.util.Map.of())));
        }
        return rows;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String outcome(FrictionAttemptRecord record) {
        return record.outcome() == null ? null : record.outcome().name();
    }
}
