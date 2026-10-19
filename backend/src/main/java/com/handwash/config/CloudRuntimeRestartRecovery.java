package com.handwash.config;

import com.handwash.service.SessionAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Marks volatile sessions interrupted before the cloud instance accepts traffic. */
@Component
@Profile("cloud")
public final class CloudRuntimeRestartRecovery implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(CloudRuntimeRestartRecovery.class);
    private final SessionAuditService auditService;

    public CloudRuntimeRestartRecovery(SessionAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int interrupted = auditService.interruptNonTerminalEncountersAfterRestart();
        if (interrupted > 0) {
            log.warn("Cloud startup marked {} previous encounter(s) INTERRUPTED; session state is not resumed.",
                interrupted);
        }
    }
}
