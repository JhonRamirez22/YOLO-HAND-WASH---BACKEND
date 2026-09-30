package com.handwash.service.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import com.handwash.service.SessionAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/** Deletes photos when their retention ends and closes uploads that can no longer arrive. */
@Component
@Profile("cloud")
public class EvidenceRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(EvidenceRetentionJob.class);
    private static final int BATCH = 100;
    private final WashEvidenceRepository repository;
    private final EvidenceStore store;
    private final SessionAuditService audit;
    private final LongSupplier clockMillis;

    @Autowired
    public EvidenceRetentionJob(WashEvidenceRepository repository, EvidenceStore store, SessionAuditService audit) {
        this(repository, store, audit, System::currentTimeMillis);
    }

    EvidenceRetentionJob(WashEvidenceRepository repository, EvidenceStore store, SessionAuditService audit,
                         LongSupplier clockMillis) {
        this.repository = repository;
        this.store = store;
        this.audit = audit;
        this.clockMillis = clockMillis;
    }

    @Scheduled(initialDelayString = "${handwash.evidence.cleanup-initial-delay-ms:60000}",
        fixedDelayString = "${handwash.evidence.cleanup-ms:600000}")
    public void run() {
        long now = clockMillis.getAsLong();
        try {
            repository.markMissed(now - EvidenceProperties.UPLOAD_WINDOW_MS);
            for (var expired : repository.findExpired(now, BATCH)) {
                try {
                    store.delete(expired.objectKey());
                    repository.markExpired(expired.sessionId(), expired.attemptNumber());
                    audit.recordStationEvent(expired.sessionId(), "SYSTEM", "EVIDENCE_PHOTO_EXPIRED", "EXPIRED",
                        "ATTEMPT_" + expired.attemptNumber());
                } catch (RuntimeException failure) {
                    // Keep the row: the next run retries instead of orphaning a photo.
                    log.warn("Photo retention cleanup failed: {}", failure.getClass().getSimpleName());
                }
            }
        } catch (DataAccessException unavailable) {
            log.debug("Photo retention storage unavailable: {}", unavailable.getClass().getSimpleName());
        }
    }
}
