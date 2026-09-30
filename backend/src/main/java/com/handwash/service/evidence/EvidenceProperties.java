package com.handwash.service.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-level photo settings; the ADMIN kill switch lives in the database. */
@Component
public class EvidenceProperties {
    public static final int MAX_UPLOAD_BYTES = 300 * 1024;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 90;
    /** How long the kiosk has to upload after an attempt is reported. */
    public static final long UPLOAD_WINDOW_MS = 5 * 60_000L;
    private final boolean captureEnabled;
    private final String bucket;
    private final String kmsKeyId;
    private final int retentionDays;
    private final String region;

    public EvidenceProperties(@Value("${handwash.evidence.capture-enabled:false}") boolean captureEnabled,
                              @Value("${handwash.evidence.bucket:}") String bucket,
                              @Value("${handwash.evidence.kms-key-id:}") String kmsKeyId,
                              @Value("${handwash.evidence.retention-days:30}") int retentionDays,
                              @Value("${handwash.evidence.region:sa-east-1}") String region) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("handwash.evidence.retention-days debe estar entre 1 y 90");
        }
        this.captureEnabled = captureEnabled;
        this.bucket = bucket == null ? "" : bucket.trim();
        this.kmsKeyId = kmsKeyId == null ? "" : kmsKeyId.trim();
        this.retentionDays = retentionDays;
        this.region = region == null || region.isBlank() ? "sa-east-1" : region.trim();
    }

    public boolean captureEnabled() { return captureEnabled; }
    public String bucket() { return bucket; }
    public String kmsKeyId() { return kmsKeyId; }
    public int retentionDays() { return retentionDays; }
    public String region() { return region; }

    /** Photos are only ever stored encrypted with a customer-managed key. */
    public boolean configured() { return !bucket.isEmpty() && !kmsKeyId.isEmpty(); }
}
