package com.handwash.service.audit;

import com.handwash.model.SessionAuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Recomputes the chain end to end and compares the tail with the head row and with the latest seal.
 * It detects edited, deleted, inserted and unhashed events; truncating the tail together with the
 * head row is only caught by the sealed copy in the write-once bucket.
 */
@Service
public class AuditChainVerifier {
    public record Problem(Long eventId, String code) {}
    public record Report(boolean ok, boolean keyed, long checked, Long firstChainedEventId, Long lastEventId,
                         String headHash, boolean matchesLatestSeal, Long latestSealId, List<Problem> problems) {}

    private static final int BATCH = 2_000;
    private static final int MAX_PROBLEMS = 10;
    private final JdbcTemplate jdbc;
    private final AuditChainHasher hasher;

    public AuditChainVerifier(JdbcTemplate jdbc, AuditChainHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    public Report verify() {
        List<Problem> problems = new ArrayList<>();
        Long first = jdbc.queryForObject("SELECT MIN(event_id) FROM wash_audit_events WHERE event_hash IS NOT NULL", Long.class);
        String expectedPrevious = AuditChainHasher.GENESIS;
        long checked = 0;
        Long lastId = null;
        String lastHash = AuditChainHasher.GENESIS;
        long cursor = first == null ? Long.MAX_VALUE : first - 1;
        boolean more = first != null;
        while (more) {
            List<Row> rows = jdbc.query("""
                SELECT event_id, session_id, event_type, actor_subject, step_code, decision_code, reason_code,
                       attempt_number, frame_sequence, confidence, occurred_at_epoch_ms, prev_hash, event_hash
                FROM wash_audit_events WHERE event_id > ? ORDER BY event_id LIMIT ?
                """, (rs, n) -> new Row(new SessionAuditEvent(rs.getLong("event_id"), rs.getString("session_id"),
                    rs.getString("event_type"), rs.getString("actor_subject"), rs.getString("step_code"),
                    rs.getString("decision_code"), rs.getString("reason_code"),
                    rs.getObject("attempt_number", Integer.class), rs.getObject("frame_sequence", Long.class),
                    rs.getObject("confidence", Double.class), rs.getLong("occurred_at_epoch_ms")),
                    trim(rs.getString("prev_hash")), trim(rs.getString("event_hash"))), cursor, BATCH);
            for (Row row : rows) {
                checked++;
                long id = row.event().eventId();
                cursor = id;
                if (row.hash() == null || row.prev() == null) {
                    problem(problems, id, "UNHASHED_EVENT");
                    continue;
                }
                if (!row.prev().equals(expectedPrevious)) problem(problems, id, "BROKEN_LINK");
                String recomputed = hasher.linkHash(row.prev(), row.event());
                if (!recomputed.equals(row.hash())) problem(problems, id, "HASH_MISMATCH");
                expectedPrevious = row.hash();
                lastId = id;
                lastHash = row.hash();
            }
            more = rows.size() == BATCH;
        }

        var head = jdbc.queryForObject("SELECT last_event_id, last_hash FROM audit_chain_head WHERE id = 1",
            (rs, n) -> new Object[] {rs.getObject("last_event_id", Long.class), trim(rs.getString("last_hash"))});
        if (!java.util.Objects.equals(head[0], lastId) || !lastHash.equals(head[1])) {
            problem(problems, lastId, "HEAD_MISMATCH");
        }

        Long sealId = null;
        boolean sealOk = true;
        var seal = jdbc.query("SELECT seal_id, to_event_id, head_hash FROM audit_seals ORDER BY seal_id DESC LIMIT 1",
            (rs, n) -> new Object[] {rs.getLong("seal_id"), rs.getObject("to_event_id", Long.class), trim(rs.getString("head_hash"))})
            .stream().findFirst();
        if (seal.isPresent()) {
            sealId = (Long) seal.get()[0];
            Long sealedEvent = (Long) seal.get()[1];
            if (sealedEvent != null) {
                String stored = jdbc.query("SELECT event_hash FROM wash_audit_events WHERE event_id = ?",
                    (rs, n) -> trim(rs.getString(1)), sealedEvent).stream().findFirst().orElse(null);
                sealOk = seal.get()[2].equals(stored);
                if (!sealOk) problem(problems, sealedEvent, "SEAL_MISMATCH");
            }
        }
        return new Report(problems.isEmpty(), hasher.keyed(), checked, first, lastId, lastHash, sealOk, sealId,
            List.copyOf(problems));
    }

    private static void problem(List<Problem> problems, Long id, String code) {
        if (problems.size() < MAX_PROBLEMS) problems.add(new Problem(id, code));
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }

    private record Row(SessionAuditEvent event, String prev, String hash) {}
}
