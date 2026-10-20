package com.handwash.ops;

import org.slf4j.Logger;

/**
 * Operational alerts as stable log lines. CloudWatch metric filters match the exact token
 * {@code OPS_ALERT code=<CODE>}, so renaming a code means updating the alarms in the CDK stack.
 * Messages carry the code and a bounded, non-personal detail only.
 */
public final class OpsAlert {
    public enum Code {
        FRICTION_PERSISTENCE_FAILED,
        AUDIT_CHAIN_BROKEN,
        PRODUCER_SILENT,
        SHIFT_SESSION_START_FAILED,
        PHOTO_UPLOAD_UNAVAILABLE,
        AUDIT_SEAL_FAILED
    }

    private OpsAlert() {}

    public static void raise(Logger log, Code code, String detail) {
        String safe = detail == null ? "" : detail.replace("OPS_ALERT", "").replaceAll("[^A-Za-z0-9_.:=, -]", "_");
        log.warn("OPS_ALERT code={} {}", code.name(), safe.length() > 120 ? safe.substring(0, 120) : safe);
    }
}
