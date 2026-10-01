package com.handwash.service.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import com.handwash.ops.OpsAlert;
import com.handwash.service.SessionAuditService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Stores the photo of a REPORTADO attempt, once. Java decides whether a photo may exist at all:
 * capture must be active, the attempt must be a finished report still waiting for its photo, and
 * the upload must arrive inside the window. The bytes are re-encoded before they are stored.
 */
@Service
@Profile("cloud")
public class EvidenceUploadService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EvidenceUploadService.class);
    public enum Outcome { STORED, DISABLED, NOT_ELIGIBLE, ALREADY_STORED, INVALID_IMAGE, UNAVAILABLE }

    private final EvidenceSettingsService settings;
    private final EvidenceProperties properties;
    private final WashEvidenceRepository repository;
    private final EvidenceStore store;
    private final SessionAuditService audit;
    private final LongSupplier clockMillis;

    @Autowired
    public EvidenceUploadService(EvidenceSettingsService settings, EvidenceProperties properties,
                                 WashEvidenceRepository repository, EvidenceStore store, SessionAuditService audit) {
        this(settings, properties, repository, store, audit, System::currentTimeMillis);
    }

    EvidenceUploadService(EvidenceSettingsService settings, EvidenceProperties properties,
                          WashEvidenceRepository repository, EvidenceStore store, SessionAuditService audit,
                          LongSupplier clockMillis) {
        this.settings = settings;
        this.properties = properties;
        this.repository = repository;
        this.store = store;
        this.audit = audit;
        this.clockMillis = clockMillis;
    }

    public Outcome upload(String sessionId, int attemptNumber, byte[] image) {
        try {
            if (!settings.captureActive()) return Outcome.DISABLED;
            var attempt = repository.findAttempt(sessionId, attemptNumber);
            long now = clockMillis.getAsLong();
            if (attempt.isEmpty() || !attempt.get().terminal() || !"REPORTADO".equals(attempt.get().outcome())) {
                return Outcome.NOT_ELIGIBLE;
            }
            if ("CAPTURADA".equals(attempt.get().evidenceStatus())) return Outcome.ALREADY_STORED;
            Long finishedAt = attempt.get().finishedAtEpochMs();
            if (!"PENDIENTE".equals(attempt.get().evidenceStatus()) || finishedAt == null
                || now - finishedAt > EvidenceProperties.UPLOAD_WINDOW_MS) {
                return Outcome.NOT_ELIGIBLE;
            }
            final byte[] clean;
            try {
                clean = ImageSanitizer.sanitize(image, EvidenceProperties.MAX_UPLOAD_BYTES);
            } catch (IllegalArgumentException invalid) {
                return Outcome.INVALID_IMAGE;
            }
            String key = "reports/" + Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()
                .substring(0, 7) + "/" + sessionId + "-" + attemptNumber + "-" + UUID.randomUUID() + ".jpg";
            store.put(key, clean);
            try {
                repository.store(sessionId, attemptNumber, key, sha256(clean), now,
                    now + TimeUnit.DAYS.toMillis(properties.retentionDays()));
            } catch (RuntimeException databaseFailure) {
                deleteQuietly(key); // an object without a reference would never be cleaned up
                return databaseFailure instanceof org.springframework.dao.DuplicateKeyException
                    ? Outcome.ALREADY_STORED : Outcome.UNAVAILABLE;
            }
            audit.recordStationEvent(sessionId, "STATION", "EVIDENCE_PHOTO_STORED", "STORED", "ATTEMPT_" + attemptNumber);
            return Outcome.STORED;
        } catch (RuntimeException unavailable) {
            OpsAlert.raise(log, OpsAlert.Code.PHOTO_UPLOAD_UNAVAILABLE, "cause=" + unavailable.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    private void deleteQuietly(String key) {
        try { store.delete(key); } catch (RuntimeException ignored) { /* the retention job reports leftovers */ }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
