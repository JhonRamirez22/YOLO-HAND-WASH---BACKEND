package com.handwash.service.evidence;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Cloud adapter: a reported wash waits for its photo only while capture is active. */
@Component
@Profile("cloud")
@Primary
public final class ConfiguredReportEvidence implements ReportEvidencePort {
    private final EvidenceSettingsService settings;

    public ConfiguredReportEvidence(EvidenceSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public EvidenceStatus solicitarCaptura(String sessionId, int attemptNumber) {
        return settings.captureActive() ? EvidenceStatus.PENDIENTE : EvidenceStatus.DESACTIVADA;
    }
}
