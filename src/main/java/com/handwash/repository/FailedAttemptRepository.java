package com.handwash.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.handwash.model.IntentoLavadoResumen;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/** Persists only bounded failure summaries; it never receives frames or normal detections. */
@Repository
public class FailedAttemptRepository implements FailedAttemptStore {
    private static final int MAX_ATTEMPTS_PER_SESSION = 100;
    private static final int MAX_RETAINED_SESSION_IDS = 4_096;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FailedAttemptRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Idempotent by session and attempt number, so retries cannot duplicate a failure. */
    public void insertIfAbsent(String sessionId, IntentoLavadoResumen attempt) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(attempt);
        } catch (JacksonException error) {
            throw new IllegalStateException("No se pudo serializar el resumen del intento fallido", error);
        }

        try {
            jdbcTemplate.update("""
                INSERT INTO failed_attempts
                    (session_id, attempt_number, result, reason, duration_ms, payload_json, created_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                attempt.numero(),
                attempt.resultado(),
                attempt.motivoReinicio(),
                attempt.duracionMs(),
                payload,
                System.currentTimeMillis());
        } catch (DuplicateKeyException ignored) {
            // The same closed attempt may be retried after a transient storage failure.
        }
        jdbcTemplate.update("""
            DELETE FROM failed_attempts
            WHERE session_id = ?
              AND attempt_number NOT IN (
                  SELECT attempt_number FROM failed_attempts
                  WHERE session_id = ?
                  ORDER BY attempt_number DESC
                  LIMIT ?
              )
            """, sessionId, sessionId, MAX_ATTEMPTS_PER_SESSION);
    }

    public List<IntentoLavadoResumen> findBySession(String sessionId) {
        return jdbcTemplate.query("""
                SELECT payload_json
                FROM failed_attempts
                WHERE session_id = ?
                ORDER BY attempt_number ASC
                """,
            (resultSet, rowNumber) -> {
                try {
                    return objectMapper.readValue(resultSet.getString("payload_json"), IntentoLavadoResumen.class);
                } catch (JacksonException error) {
                    throw new IllegalStateException("Resumen de intento almacenado con JSON inválido", error);
                }
            },
            sessionId);
    }

    public int deleteBySession(String sessionId) {
        return jdbcTemplate.update("DELETE FROM failed_attempts WHERE session_id = ?", sessionId);
    }

    public int deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds) {
        List<String> retained = List.copyOf(Objects.requireNonNull(
            retainedSessionIds, "retainedSessionIds es obligatorio para limpiar la caché"));
        if (retained.size() > MAX_RETAINED_SESSION_IDS) {
            throw new IllegalArgumentException("La lista de sesiones protegidas excede el límite soportado");
        }
        if (retained.isEmpty()) {
            return jdbcTemplate.update(
                "DELETE FROM failed_attempts WHERE created_at_epoch_ms < ?", cutoffEpochMs);
        }

        // Only placeholder count is interpolated; session identifiers remain bound values.
        String placeholders = String.join(",", java.util.Collections.nCopies(retained.size(), "?"));
        List<Object> parameters = new ArrayList<>(retained.size() + 1);
        parameters.add(cutoffEpochMs);
        parameters.addAll(retained);
        return jdbcTemplate.update(
            "DELETE FROM failed_attempts WHERE created_at_epoch_ms < ? AND session_id NOT IN ("
                + placeholders + ")",
            parameters.toArray());
    }
}
