package com.handwash.repository;

import com.handwash.model.StationShift;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class StationShiftRepository {
    private static final String COLUMNS = """
        shift_id, station_id, protocol_code, started_by, started_at_epoch_ms, ends_at_epoch_ms,
        ended_at_epoch_ms, end_reason""";
    private static final RowMapper<StationShift> MAPPER = (rs, row) -> new StationShift(
        rs.getString("shift_id"), rs.getString("station_id"), rs.getString("protocol_code"),
        rs.getString("started_by"), rs.getLong("started_at_epoch_ms"), rs.getLong("ends_at_epoch_ms"),
        rs.getObject("ended_at_epoch_ms", Long.class),
        rs.getString("end_reason") == null ? null : StationShift.ShiftEndReason.valueOf(rs.getString("end_reason")));
    private final JdbcTemplate jdbc;

    public StationShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(StationShift shift) {
        jdbc.update("INSERT INTO station_shifts (" + COLUMNS + ", open_station_id) VALUES (?,?,?,?,?,?,?,?,?)",
            shift.shiftId(), shift.stationId(), shift.protocolCode(), shift.startedBy(),
            shift.startedAtEpochMs(), shift.endsAtEpochMs(), shift.endedAtEpochMs(),
            shift.endReason() == null ? null : shift.endReason().name(),
            shift.open() ? shift.stationId() : null);
    }

    public Optional<StationShift> findOpenByStation(String stationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM station_shifts WHERE station_id = ? AND ended_at_epoch_ms IS NULL",
            MAPPER, stationId).stream().findFirst();
    }

    public List<StationShift> findOpen() {
        return jdbc.query("SELECT " + COLUMNS + " FROM station_shifts WHERE ended_at_epoch_ms IS NULL ORDER BY started_at_epoch_ms",
            MAPPER);
    }

    /** Returns 0 when the shift was already closed, so a race closes it only once. */
    public int close(String shiftId, long endedAtEpochMs, StationShift.ShiftEndReason reason) {
        return jdbc.update("UPDATE station_shifts SET ended_at_epoch_ms = ?, end_reason = ?, open_station_id = NULL WHERE shift_id = ? AND ended_at_epoch_ms IS NULL",
            endedAtEpochMs, reason.name(), shiftId);
    }
}
