package com.handwash.decorator.repository;

import com.handwash.model.HandwashingAttemptSummary;
import com.handwash.repository.FailedAttemptStore;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Defense-in-depth decorator for the persistence boundary. SQL values are still
 * bound by JdbcTemplate placeholders in the delegate; this wrapper additionally
 * restricts session identifiers to the UUID format issued by the application.
 */
public final class SqlInjectionGuardFailedAttemptStoreDecorator implements FailedAttemptStore {
    private static final Pattern UUID_FORMAT = Pattern.compile(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final FailedAttemptStore delegate;

    public SqlInjectionGuardFailedAttemptStoreDecorator(FailedAttemptStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void insertIfAbsent(String sessionId, HandwashingAttemptSummary attempt) {
        validarSessionId(sessionId);
        if (attempt == null || attempt.numero() < 1 || attempt.duracionMs() < 0) {
            throw new IllegalArgumentException("Resumen de intento inválido");
        }
        delegate.insertIfAbsent(sessionId, attempt);
    }

    @Override
    public List<HandwashingAttemptSummary> findBySession(String sessionId) {
        validarSessionId(sessionId);
        return delegate.findBySession(sessionId);
    }

    @Override
    public int deleteBySession(String sessionId) {
        validarSessionId(sessionId);
        return delegate.deleteBySession(sessionId);
    }

    @Override
    public int deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds) {
        Set<String> retained = Objects.requireNonNull(retainedSessionIds,
            "retainedSessionIds es obligatorio para limpiar la caché");
        retained.forEach(this::validarSessionId);
        return delegate.deleteCreatedBefore(cutoffEpochMs, retained);
    }

    private void validarSessionId(String sessionId) {
        if (sessionId == null || !UUID_FORMAT.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId debe ser un UUID canónico");
        }
        // Parse as a second guard against malformed UUID encodings.
        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("sessionId debe ser un UUID canónico", malformed);
        }
    }
}
