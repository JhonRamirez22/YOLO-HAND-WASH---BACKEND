package com.handwash.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Change-control settings, the model release registry and the validation reports linked to it. */
@Repository
public class ChangeControlRepository {
    public record Settings(boolean secondApproverRequired, String updatedBy, long updatedAtEpochMs) {}
    public record ModelRow(String modelSha256, String modelVersion, String datasetVersion, long firstSeenEpochMs,
                           long lastSeenEpochMs, long encounters, long reports) {}
    public record Report(long reportId, String modelSha256, Integer profileVersion, String title, String summary,
                         String documentSha256, String createdBy, long createdAtEpochMs) {}

    private final JdbcTemplate jdbc;

    public ChangeControlRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Settings settings() {
        return jdbc.query("SELECT second_approver_required, updated_by, updated_at_epoch_ms FROM change_control_settings WHERE id = 1",
            (rs, n) -> new Settings(rs.getBoolean(1), rs.getString(2), rs.getLong(3))).stream().findFirst()
            .orElse(new Settings(false, "SYSTEM", 0L));
    }

    public void setSecondApproverRequired(boolean required, String actor, long nowEpochMs) {
        jdbc.update("UPDATE change_control_settings SET second_approver_required = ?, updated_by = ?, updated_at_epoch_ms = ? WHERE id = 1",
            required, actor, nowEpochMs);
    }

    /** Idempotent: the same release is recorded once, with the time it was first seen. */
    public boolean registerRelease(String sha256, String modelVersion, String datasetVersion, long nowEpochMs) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM model_releases WHERE model_sha256 = ? AND model_version = ? AND dataset_version = ?",
            Integer.class, sha256, modelVersion, datasetVersion);
        if (existing != null && existing > 0) return false;
        jdbc.update("INSERT INTO model_releases (model_sha256, model_version, dataset_version, first_seen_epoch_ms) VALUES (?,?,?,?)",
            sha256, modelVersion, datasetVersion, nowEpochMs);
        return true;
    }

    /** Releases the server declared plus every model a producer ever reported in an encounter. */
    public List<ModelRow> models() {
        return jdbc.query("""
            SELECT u.model_sha256, u.model_version, u.dataset_version, MIN(u.seen) AS first_seen, MAX(u.seen) AS last_seen,
                   SUM(u.encounters) AS encounters,
                   (SELECT COUNT(*) FROM validation_reports r WHERE r.model_sha256 = u.model_sha256) AS reports
            FROM (
                SELECT model_sha256, model_version, dataset_version, first_seen_epoch_ms AS seen, 0 AS encounters FROM model_releases
                UNION ALL
                SELECT model_sha256, model_version, dataset_version, started_at_epoch_ms AS seen, 1 AS encounters FROM wash_encounters
            ) u
            GROUP BY u.model_sha256, u.model_version, u.dataset_version
            ORDER BY MAX(u.seen) DESC""",
            (rs, n) -> new ModelRow(rs.getString(1).trim(), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7)));
    }

    public long addReport(String modelSha256, Integer profileVersion, String title, String summary, String documentSha256,
                          String createdBy, long nowEpochMs) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO validation_reports (model_sha256, profile_version, title, summary, document_sha256, created_by, created_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?)""", new String[] {"report_id"});
            statement.setString(1, modelSha256);
            statement.setObject(2, profileVersion);
            statement.setString(3, title);
            statement.setString(4, summary);
            statement.setString(5, documentSha256);
            statement.setString(6, createdBy);
            statement.setLong(7, nowEpochMs);
            return statement;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public List<Report> reports(int limit) {
        return jdbc.query("""
            SELECT report_id, model_sha256, profile_version, title, summary, document_sha256, created_by, created_at_epoch_ms
            FROM validation_reports ORDER BY report_id DESC LIMIT ?""",
            (rs, n) -> new Report(rs.getLong(1), rs.getString(2).trim(), rs.getObject(3, Integer.class), rs.getString(4),
                rs.getString(5), rs.getString(6).trim(), rs.getString(7), rs.getLong(8)), limit);
    }

    public boolean profileHasReport(int version) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM validation_reports WHERE profile_version = ?", Integer.class, version);
        return count != null && count > 0;
    }
}
