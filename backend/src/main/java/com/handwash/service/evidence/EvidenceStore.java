package com.handwash.service.evidence;

import java.util.Optional;

/** Where encrypted photos live. Production uses S3 with KMS; nothing else ever sees the bytes. */
public interface EvidenceStore {
    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    void delete(String key);

    /** Raised for storage failures so callers never confuse them with "not found". */
    class EvidenceStoreException extends RuntimeException {
        public EvidenceStoreException(String message, Throwable cause) { super(message, cause); }
        public EvidenceStoreException(String message) { super(message); }
    }
}
