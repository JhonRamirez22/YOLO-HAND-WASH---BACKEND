package com.handwash.security;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LocalViewerCredentialTest {
    @Test void repeatedLocalPairingDoesNotRotateViewerDeviceOwnerOrExtendTtl() {
        var clock = new AtomicLong();
        var registry = new SessionCredentialRegistry(clock::get, () -> 10000L);
        registry.configureTtl(1000);
        registry.createCredentials("session");
        String device = registry.getDeviceToken("session"), owner = registry.getOwnerToken("session");
        var first = registry.reuseOrRefreshViewerToken("session");
        clock.set(500_000_000L);
        var second = registry.reuseOrRefreshViewerToken("session");
        assertEquals(first, second);
        assertEquals(device, registry.getDeviceToken("session"));
        assertEquals(owner, registry.getOwnerToken("session"));
        assertEquals(SessionCredentialRegistry.Role.VIEWER, registry.authenticate("session", first.value()).role());
        clock.set(1_000_000_001L);
        assertNull(registry.authenticate("session", first.value()));
        var renewed = registry.reuseOrRefreshViewerToken("session");
        assertNotEquals(first.value(), renewed.value());
        assertNull(registry.reuseOrRefreshViewerToken("absent"));
    }
}
