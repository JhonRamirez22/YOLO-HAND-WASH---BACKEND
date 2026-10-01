package com.handwash.service.evidence;

/**
 * Port for the photo attached to a REPORTADO attempt. A future adapter takes the frame
 * held in RAM by the presence camera, stores it encrypted (S3 + KMS) and records only its
 * reference and hash. Implementations must be idempotent per session/attempt because a
 * failed database write retries the same decision.
 */
public interface ReportEvidencePort {
    EvidenceStatus solicitarCaptura(String sessionId, int attemptNumber);
}
