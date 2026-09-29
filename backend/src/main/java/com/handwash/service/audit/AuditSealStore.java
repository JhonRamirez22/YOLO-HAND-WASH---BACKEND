package com.handwash.service.audit;

/** Write-once destination of the daily seals (an Object Lock bucket in the cloud profile). */
public interface AuditSealStore {
    /** @throws RuntimeException when the object could not be stored */
    void put(String key, byte[] bytes);

    boolean durable();
}
