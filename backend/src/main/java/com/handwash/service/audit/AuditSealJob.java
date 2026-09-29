package com.handwash.service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Seals about once a day: it checks every 10 minutes and the service decides whether anything is new. */
@Component
@Profile("cloud")
public class AuditSealJob {
    private static final Logger log = LoggerFactory.getLogger(AuditSealJob.class);
    private static final long DAY_MS = 86_400_000L;
    private final AuditSealService seals;

    public AuditSealJob(AuditSealService seals) { this.seals = seals; }

    @Scheduled(initialDelayString = "${handwash.audit.seal-initial-delay-ms:120000}",
        fixedDelayString = "${handwash.audit.seal-check-ms:600000}")
    public void run() {
        try {
            var latest = seals.recent(1);
            if (!latest.isEmpty() && System.currentTimeMillis() - latest.get(0).sealedAtEpochMs() < DAY_MS) return;
            seals.sealIfNeeded();
        } catch (DataAccessException | IllegalStateException unavailable) {
            log.warn("Audit seal postponed: {}", unavailable.getClass().getSimpleName());
        }
    }
}
