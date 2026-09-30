package com.handwash.service.evidence;

import org.springframework.beans.factory.annotation.Autowired;
import com.handwash.service.SessionAuditService;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

/**
 * Photo capture is active only when the deployment allows it, the bucket and key are configured
 * and an ADMIN has switched it on. Turning it off takes effect on the next request.
 */
@Service
public class EvidenceSettingsService {
    public record View(boolean deploymentEnabled, boolean configured, boolean switchOn, boolean captureActive,
                       int retentionDays, String updatedBy, long updatedAtEpochMs) {}

    private final EvidenceProperties properties;
    private final EvidenceSettingsRepository repository;
    private final SessionAuditService audit;
    private final LongSupplier clockMillis;

    @Autowired
    public EvidenceSettingsService(EvidenceProperties properties, EvidenceSettingsRepository repository,
                                   SessionAuditService audit) {
        this(properties, repository, audit, System::currentTimeMillis);
    }

    EvidenceSettingsService(EvidenceProperties properties, EvidenceSettingsRepository repository,
                            SessionAuditService audit, LongSupplier clockMillis) {
        this.properties = properties;
        this.repository = repository;
        this.audit = audit;
        this.clockMillis = clockMillis;
    }

    public boolean captureActive() {
        return properties.captureEnabled() && properties.configured() && repository.get().captureEnabled();
    }

    public View view() {
        var settings = repository.get();
        boolean active = properties.captureEnabled() && properties.configured() && settings.captureEnabled();
        return new View(properties.captureEnabled(), properties.configured(), settings.captureEnabled(), active,
            properties.retentionDays(), settings.updatedBy(), settings.updatedAtEpochMs());
    }

    /** @throws IllegalStateException when switching on without a deployment that allows photos */
    public View setEnabled(boolean enabled, String actor) {
        if (enabled && !(properties.captureEnabled() && properties.configured())) {
            throw new IllegalStateException("FOTO_NO_CONFIGURADA");
        }
        repository.set(enabled, actor, clockMillis.getAsLong());
        audit.recordStationEvent(null, actor, enabled ? "ADMIN_EVIDENCE_ENABLED" : "ADMIN_EVIDENCE_DISABLED",
            enabled ? "ENABLED" : "DISABLED", null);
        return view();
    }
}
