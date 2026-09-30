package com.handwash.service.audit;

/** No bucket configured: seals stay in the database only and the verifier says so. */
public final class UnavailableAuditSealStore implements AuditSealStore {
    @Override public void put(String key, byte[] bytes) { throw new IllegalStateException("AUDIT_SEAL_BUCKET_NOT_CONFIGURED"); }
    @Override public boolean durable() { return false; }
}
