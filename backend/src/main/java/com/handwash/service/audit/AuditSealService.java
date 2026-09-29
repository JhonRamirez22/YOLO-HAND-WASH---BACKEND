package com.handwash.service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Seals the audit log: records the chain head, a digest of the washes finished in the window and an
 * HMAC signature, and exports the same document to the write-once store. A later rewrite of history
 * cannot match the copy that was already outside the database.
 */
@Service
public class AuditSealService {
    private static final Logger log = LoggerFactory.getLogger(AuditSealService.class);

    public record Seal(long sealId, long sealedAtEpochMs, long windowStartEpochMs, Long toEventId, String headHash,
                       long eventCount, String resultsDigest, long resultsCount, boolean keyed, String signature,
                       String objectKey) {}

    private final JdbcTemplate jdbc;
    private final AuditChainHasher hasher;
    private final AuditSealStore store;
    private final LongSupplier clockMillis;

    @Autowired
    public AuditSealService(JdbcTemplate jdbc, AuditChainHasher hasher, AuditSealStore store) {
        this(jdbc, hasher, store, System::currentTimeMillis);
    }

    AuditSealService(JdbcTemplate jdbc, AuditChainHasher hasher, AuditSealStore store, LongSupplier clockMillis) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.store = store;
        this.clockMillis = clockMillis;
    }

    /** Seals everything up to now unless nothing happened since the previous seal. Returns the new seal, if any. */
    public synchronized java.util.Optional<Seal> sealIfNeeded() {
        long now = clockMillis.getAsLong();
        var previous = latest();
        long windowStart = previous.map(Seal::sealedAtEpochMs).orElse(0L);
        var head = jdbc.queryForObject("SELECT last_event_id, last_hash FROM audit_chain_head WHERE id = 1",
            (rs, n) -> new Object[] {rs.getObject("last_event_id", Long.class), rs.getString("last_hash").trim()});
        Long toEvent = (Long) head[0];
        if (previous.isPresent() && java.util.Objects.equals(previous.get().toEventId(), toEvent)
            && resultsInWindow(windowStart, now).count() == 0) {
            return java.util.Optional.empty();
        }
        long eventCount = toEvent == null ? 0 : countEventsUpTo(toEvent);
        var results = resultsInWindow(windowStart, now);
        String canonical = String.join("|", "v1", String.valueOf(now), String.valueOf(windowStart),
            String.valueOf(toEvent), (String) head[1], String.valueOf(eventCount), results.digest(),
            String.valueOf(results.count()), String.valueOf(hasher.keyed()));
        String signature = hasher.sign(canonical);
        String key = null;
        if (store.durable()) {
            key = "seals/" + Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate() + "-" + now + ".json";
            String document = "{\"version\":\"v1\",\"sealedAtEpochMs\":" + now + ",\"windowStartEpochMs\":" + windowStart
                + ",\"toEventId\":" + toEvent + ",\"headHash\":\"" + head[1] + "\",\"eventCount\":" + eventCount
                + ",\"resultsDigest\":\"" + results.digest() + "\",\"resultsCount\":" + results.count()
                + ",\"keyed\":" + hasher.keyed() + ",\"signature\":\"" + signature + "\"}";
            try {
                store.put(key, document.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException exportFailure) {
                // A seal that is not out of the database yet is retried by the next run, not recorded.
                com.handwash.ops.OpsAlert.raise(log, com.handwash.ops.OpsAlert.Code.AUDIT_SEAL_FAILED,
                    "cause=" + exportFailure.getClass().getSimpleName());
                throw exportFailure;
            }
        }
        jdbc.update("""
            INSERT INTO audit_seals (sealed_at_epoch_ms, window_start_epoch_ms, to_event_id, head_hash, event_count,
                                     results_digest, results_count, keyed, signature, object_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", now, windowStart, toEvent, head[1], eventCount,
            results.digest(), results.count(), hasher.keyed(), signature, key);
        return latest();
    }

    public List<Seal> recent(int limit) {
        return jdbc.query("""
            SELECT seal_id, sealed_at_epoch_ms, window_start_epoch_ms, to_event_id, head_hash, event_count,
                   results_digest, results_count, keyed, signature, object_key
            FROM audit_seals ORDER BY seal_id DESC LIMIT ?""", (rs, n) -> new Seal(rs.getLong("seal_id"),
            rs.getLong("sealed_at_epoch_ms"), rs.getLong("window_start_epoch_ms"), rs.getObject("to_event_id", Long.class),
            rs.getString("head_hash").trim(), rs.getLong("event_count"), rs.getString("results_digest").trim(),
            rs.getLong("results_count"), rs.getBoolean("keyed"), rs.getString("signature").trim(),
            rs.getString("object_key")), limit);
    }

    /** True when the stored digest of a past window still matches the finished washes in the database. */
    public boolean resultsStillMatch(Seal seal) {
        return resultsInWindow(seal.windowStartEpochMs(), seal.sealedAtEpochMs()).digest().equals(seal.resultsDigest());
    }

    private java.util.Optional<Seal> latest() { return recent(1).stream().findFirst(); }

    private long countEventsUpTo(long eventId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_id <= ?", Long.class, eventId);
        return count == null ? 0 : count;
    }

    private record Results(String digest, long count) {}

    /** Finished washes stored in (start, end]; evidence status is excluded because it legitimately changes later. */
    private Results resultsInWindow(long startExclusive, long endInclusive) {
        List<String> lines = jdbc.query("""
            SELECT session_id, attempt_number, result, reason, outcome, started_at_epoch_ms, finished_at_epoch_ms, duration_ms
            FROM friction_attempt_validation
            WHERE terminal = TRUE AND updated_at_epoch_ms > ? AND updated_at_epoch_ms <= ?
            ORDER BY session_id, attempt_number""",
            (rs, n) -> rs.getString(1) + "," + rs.getInt(2) + "," + rs.getString(3) + "," + rs.getString(4) + ","
                + rs.getString(5) + "," + rs.getObject(6) + "," + rs.getObject(7) + "," + rs.getObject(8),
            startExclusive, endInclusive);
        return new Results(AuditChainHasher.sha256(String.join("\n", lines)), lines.size());
    }
}
