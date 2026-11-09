package com.handwash.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CameraPairingRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final CameraPairingRegistry registry = new CameraPairingRegistry(now::get);

    @Test
    void aCodeWorksOnceAndBindsTheCameraToTheStation() {
        String code = registry.issueCode("station-1");

        var camera = registry.redeem(code);

        assertNotNull(camera);
        assertEquals("station-1", camera.stationId());
        assertEquals("station-1", registry.authenticate(camera.token()));
        assertNull(registry.redeem(code), "a code is single use");
    }

    @Test
    void anExpiredOrUnknownCodeIsRefused() {
        String code = registry.issueCode("station-1");
        now.addAndGet(CameraPairingRegistry.CODE_TTL_MS + 1);
        assertNull(registry.redeem(code));
        assertNull(registry.redeem("nope"));
        assertNull(registry.redeem(null));
    }

    @Test
    void theLastScanWinsAndTheOldCameraIsReplaced() {
        var first = registry.redeem(registry.issueCode("station-1"));
        var second = registry.redeem(registry.issueCode("station-1"));

        assertNull(registry.authenticate(first.token()), "the previous camera is replaced");
        assertEquals("station-1", registry.authenticate(second.token()));
        assertTrue(registry.isReplaced(first.token()));
    }

    @Test
    void camerasOfOtherStationsDoNotInterfere() {
        var a = registry.redeem(registry.issueCode("station-a"));
        var b = registry.redeem(registry.issueCode("station-b"));
        assertEquals("station-a", registry.authenticate(a.token()));
        assertEquals("station-b", registry.authenticate(b.token()));
    }

    @Test
    void aCameraTokenExpiresAndIsOnlyStoredHashed() {
        var camera = registry.redeem(registry.issueCode("station-1"));
        now.addAndGet(CameraPairingRegistry.TOKEN_TTL_MS + 1);
        assertNull(registry.authenticate(camera.token()));
        assertNull(registry.authenticate(""));
        assertNull(registry.authenticate(null));
    }

    @Test
    void theNumberOfLiveCodesIsBounded() {
        for (int i = 0; i < CameraPairingRegistry.MAX_LIVE_CODES; i++) registry.issueCode("station-" + i);
        assertThrows(IllegalStateException.class, () -> registry.issueCode("one-too-many"));
    }
}
