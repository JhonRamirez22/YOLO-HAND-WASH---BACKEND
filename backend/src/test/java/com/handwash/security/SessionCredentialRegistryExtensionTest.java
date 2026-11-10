package com.handwash.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shift sessions slide the owner token forward; nothing else about the credential changes. */
class SessionCredentialRegistryExtensionTest {
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private final AtomicLong epochMs = new AtomicLong(1_792_600_000_000L);
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final SessionCredentialRegistry registry =
        new SessionCredentialRegistry(nanos::get, epochMs::get);

    private void advance(long ms) {
        epochMs.addAndGet(ms);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private SessionCredentialRegistry registryWithHourTtl() {
        registry.configureTtl(HOUR);
        registry.createCredentials("s1");
        return registry;
    }

    @Test
    void extendingKeepsTheSameTokenAndRevisionButMovesTheExpiryForward() {
        registryWithHourTtl();
        String token = registry.getOwnerToken("s1");
        long firstExpiry = registry.getOwnerTokenExpirationEpochMs("s1");
        var before = registry.authenticate("s1", token);

        advance(40 * 60_000L);
        assertTrue(registry.extendOwnerToken("s1"));

        assertEquals(token, registry.getOwnerToken("s1"), "the capability value must not change");
        assertEquals(firstExpiry + 40 * 60_000L, registry.getOwnerTokenExpirationEpochMs("s1"));
        assertTrue(registry.isCurrent("s1", SessionCredentialRegistry.Role.OWNER, before.credentialRevision()),
            "tokens already handed out keep working");
        advance(30 * 60_000L); // past the original one-hour expiry
        assertNotNull(registry.authenticate("s1", token));
    }

    @Test
    void withoutExtensionTheTokenDiesAtTheBaseTtl() {
        registryWithHourTtl();
        String token = registry.getOwnerToken("s1");
        advance(HOUR + 1);
        assertNull(registry.authenticate("s1", token));
    }

    @Test
    void neverShortensRevivesOrCreatesAnything() {
        registryWithHourTtl();
        assertFalse(registry.extendOwnerToken("s1"), "a fresh token is already at the full TTL");
        assertFalse(registry.extendOwnerToken("unknown"));
        assertFalse(registry.extendOwnerToken(null));

        advance(HOUR + 1);
        assertFalse(registry.extendOwnerToken("s1"), "an expired token cannot be revived");
        assertNull(registry.getOwnerToken("s1"));
    }

    @Test
    void doesNotTouchTheDeviceOrViewerCapabilities() {
        registryWithHourTtl();
        String device = registry.getDeviceToken("s1");
        long deviceExpiry = registry.getDeviceTokenExpirationEpochMs("s1");

        advance(40 * 60_000L);
        registry.extendOwnerToken("s1");

        assertEquals(device, registry.getDeviceToken("s1"));
        assertEquals(deviceExpiry, registry.getDeviceTokenExpirationEpochMs("s1"));
    }
}
