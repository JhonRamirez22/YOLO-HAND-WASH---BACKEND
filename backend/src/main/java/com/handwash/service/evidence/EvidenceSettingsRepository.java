package com.handwash.service.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceSettingsRepository {
    public record Settings(boolean captureEnabled, String updatedBy, long updatedAtEpochMs) {}

    private final JdbcTemplate jdbc;

    public EvidenceSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings get() {
        return jdbc.query("SELECT capture_enabled, updated_by, updated_at_epoch_ms FROM evidence_settings WHERE id = 1",
            (rs, row) -> new Settings(rs.getBoolean("capture_enabled"), rs.getString("updated_by"),
                rs.getLong("updated_at_epoch_ms"))).stream().findFirst()
            .orElse(new Settings(false, "SYSTEM", 0L));
    }

    public void set(boolean enabled, String actor, long nowEpochMs) {
        jdbc.update("UPDATE evidence_settings SET capture_enabled = ?, updated_by = ?, updated_at_epoch_ms = ? WHERE id = 1",
            enabled, actor, nowEpochMs);
    }
}
