package com.handwash.service.evidence;

import org.springframework.stereotype.Component;

/**
 * Only adapter today: no presence camera exists and CloudProfileSafetyGuard keeps
 * handwash.evidence.capture-enabled=false, so a report is stored without a photo.
 */
@Component
public final class DisabledReportEvidence implements ReportEvidencePort {
    @Override
    public EvidenceStatus solicitarCaptura(String sessionId, int attemptNumber) {
        return EvidenceStatus.DESACTIVADA;
    }
}
