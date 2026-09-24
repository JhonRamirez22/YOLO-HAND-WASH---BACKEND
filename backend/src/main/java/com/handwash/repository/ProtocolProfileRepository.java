package com.handwash.repository;

import com.handwash.model.WashStep;
import com.handwash.model.ProfileChange;
import com.handwash.model.ProtocolProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/** Append-only profile versions; an existing version is never updated. */
@Repository
public class ProtocolProfileRepository {
    private static final String COLUMNS = """
        version, step_1_ms, step_2_ms, step_3_ms, step_4_ms, step_5_ms, step_6_ms,
        slow_threshold_ms, created_by, created_at_epoch_ms""";
    private static final RowMapper<ProtocolProfile> MAPPER = (rs, row) -> {
        EnumMap<WashStep, Long> tiempos = new EnumMap<>(WashStep.class);
        for (WashStep paso : ProtocolProfile.PASOS) {
            tiempos.put(paso, rs.getLong("step_" + paso.getNumero() + "_ms"));
        }
        return new ProtocolProfile(rs.getInt("version"), tiempos, rs.getLong("slow_threshold_ms"),
            rs.getString("created_by"), rs.getLong("created_at_epoch_ms"));
    };
    private static final String CHANGE_SELECT = "SELECT " + COLUMNS + ", reason, reverted_from, status, decided_by, decided_at_epoch_ms FROM protocol_profiles";
    private static final RowMapper<ProfileChange> CHANGE_MAPPER = (rs, row) -> new ProfileChange(MAPPER.mapRow(rs, row),
        rs.getString("reason"), rs.getObject("reverted_from", Integer.class), rs.getString("status"),
        rs.getString("decided_by"), rs.getObject("decided_at_epoch_ms", Long.class));
    private final JdbcTemplate jdbc;

    public ProtocolProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The version in force: the newest one that is ACTIVE (pending and rejected versions never apply). */
    public Optional<ProtocolProfile> findLatest() {
        return jdbc.query("SELECT " + COLUMNS + " FROM protocol_profiles WHERE status = 'ACTIVE' ORDER BY version DESC LIMIT 1",
            MAPPER).stream().findFirst();
    }

    /** Highest version number taken, whatever its state; new versions continue from it. */
    public int highestVersion() {
        Integer highest = jdbc.queryForObject("SELECT MAX(version) FROM protocol_profiles", Integer.class);
        return highest == null ? 0 : highest;
    }

    public List<ProtocolProfile> findRecent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM protocol_profiles ORDER BY version DESC LIMIT ?",
            MAPPER, limit);
    }

    public Optional<ProfileChange> findChange(int version) {
        return jdbc.query(CHANGE_SELECT + " WHERE version = ?", CHANGE_MAPPER, version).stream().findFirst();
    }

    public List<ProfileChange> findRecentChanges(int limit) {
        return jdbc.query(CHANGE_SELECT + " ORDER BY version DESC LIMIT ?", CHANGE_MAPPER, limit);
    }

    /** Fails with a duplicate-key error if another writer already took this version. */
    public void insert(ProtocolProfile perfil, String reason, Integer revertedFrom, String status) {
        var tiempos = perfil.tiemposPasoMs();
        jdbc.update("INSERT INTO protocol_profiles (" + COLUMNS + ", reason, reverted_from, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            perfil.version(), tiempos.get(WashStep.PASO_1_PALMAS), tiempos.get(WashStep.PASO_2_DORSOS),
            tiempos.get(WashStep.PASO_3_INTERDIGITALES), tiempos.get(WashStep.PASO_4_NUDILLOS),
            tiempos.get(WashStep.PASO_5_PULGAR), tiempos.get(WashStep.PASO_6_PUNTA_DE_DEDOS),
            perfil.umbralLentoMs(), perfil.creadoPor(), perfil.creadoEnEpochMs(), reason, revertedFrom, status);
    }

    /** Only a PENDING version can be decided, and only once. */
    public boolean decide(int version, String newStatus, String decidedBy, long nowEpochMs) {
        return jdbc.update("UPDATE protocol_profiles SET status = ?, decided_by = ?, decided_at_epoch_ms = ? WHERE version = ? AND status = 'PENDING'",
            newStatus, decidedBy, nowEpochMs, version) == 1;
    }
}
