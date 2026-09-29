package com.handwash.service.audit;

import com.handwash.ops.OpsAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Verifies the audit chain every hour so tampering raises an alarm without anyone opening the ADMIN panel. */
@Component
@Profile("cloud")
public class AuditChainVerifyJob {
    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifyJob.class);
    private final AuditChainVerifier verifier;
    private final AuditSealService seals;

    public AuditChainVerifyJob(AuditChainVerifier verifier, AuditSealService seals) {
        this.verifier = verifier;
        this.seals = seals;
    }

    @Scheduled(initialDelayString = "${handwash.audit.verify-initial-delay-ms:300000}",
        fixedDelayString = "${handwash.audit.verify-ms:3600000}")
    public void run() {
        try {
            check();
        } catch (DataAccessException unavailable) {
            log.warn("Audit chain verification postponed: {}", unavailable.getClass().getSimpleName());
        }
    }

    /** @return true when the chain, the latest seal and the sealed results all agree */
    boolean check() {
        var report = verifier.verify();
        var latest = seals.recent(1);
        boolean resultsMatch = latest.isEmpty() || seals.resultsStillMatch(latest.get(0));
        boolean intact = report.ok() && report.matchesLatestSeal() && resultsMatch;
        if (!intact) {
            OpsAlert.raise(log, OpsAlert.Code.AUDIT_CHAIN_BROKEN, "problems="
                + report.problems().stream().map(AuditChainVerifier.Problem::code).distinct().toList()
                + ",resultsMatch=" + resultsMatch);
        }
        return intact;
    }
}
