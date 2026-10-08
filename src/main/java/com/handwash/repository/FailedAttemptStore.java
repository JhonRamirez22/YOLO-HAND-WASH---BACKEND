package com.handwash.repository;

import com.handwash.model.IntentoLavadoResumen;

import java.util.List;
import java.util.Set;

/** Port for the bounded metadata-only persistence used by the wash workflow. */
public interface FailedAttemptStore {
    void insertIfAbsent(String sessionId, IntentoLavadoResumen attempt);
    List<IntentoLavadoResumen> findBySession(String sessionId);
    int deleteBySession(String sessionId);
    int deleteCreatedBefore(long cutoffEpochMs, Set<String> retainedSessionIds);
}
