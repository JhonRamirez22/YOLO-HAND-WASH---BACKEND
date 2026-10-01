package com.handwash.service.evidence;

import java.util.Optional;

/** Used when no bucket is configured: every operation fails loudly instead of dropping photos. */
public final class UnavailableEvidenceStore implements EvidenceStore {
    @Override public void put(String key, byte[] bytes) { throw unavailable(); }
    @Override public Optional<byte[]> get(String key) { throw unavailable(); }
    @Override public void delete(String key) { throw unavailable(); }

    private static EvidenceStoreException unavailable() {
        return new EvidenceStoreException("El almacenamiento de fotos no está configurado");
    }
}
