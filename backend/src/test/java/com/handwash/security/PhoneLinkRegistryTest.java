package com.handwash.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PhoneLinkRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PhoneLinkRegistry registry = new PhoneLinkRegistry(now::get);

    @Test
    void aCodeIsRedeemedExactlyOnceForTheKeyThatIssuedIt() {
        String code = registry.issue("0123456789abcdef");

        assertTrue(code.length() >= 40);
        assertEquals("0123456789abcdef", registry.redeem(code).keyId());
        assertNull(registry.redeem(code));
    }

    @Test
    void anExpiredOrUnknownCodeRedeemsNothing() {
        String code = registry.issue("0123456789abcdef");
        now.addAndGet(PhoneLinkRegistry.TTL_MS);

        assertNull(registry.redeem(code));
        assertNull(registry.redeem("x".repeat(43)));
        assertNull(registry.redeem(null));
    }

    @Test
    void theNumberOfLiveCodesIsBounded() {
        for (int i = 0; i < PhoneLinkRegistry.MAX_LIVE; i++) registry.issue("0123456789abcdef");
        assertThrows(IllegalStateException.class, () -> registry.issue("0123456789abcdef"));
        now.addAndGet(PhoneLinkRegistry.TTL_MS);
        assertNotNull(registry.issue("0123456789abcdef"));
    }

    @Test
    void aNewCodeForTheSameKeyDoesNotKillTheEarlierOneUntilItExpires() {
        String first = registry.issue("0123456789abcdef");
        String second = registry.issue("0123456789abcdef");
        assertNotEquals(first, second);
        assertEquals("0123456789abcdef", registry.redeem(first).keyId());
        assertEquals("0123456789abcdef", registry.redeem(second).keyId());
    }

    @Test
    void anAdminLinkCanLastLongerAndRemembersWhetherItLinksTheStationScreen() {
        String mac = registry.issue("0123456789abcdef", 3_600_000L, true);
        String phone = registry.issue("0123456789abcdef", 3_600_000L, false);
        now.addAndGet(1_800_000L);

        assertTrue(registry.redeem(mac).linkStation());
        assertFalse(registry.redeem(phone).linkStation());
    }

    @Test
    void theLifetimeIsCappedAtADayAndFloorOfAMinute() {
        String code = registry.issue("0123456789abcdef", 10L * 86_400_000L, false);
        now.addAndGet(86_400_000L + 1);
        assertNull(registry.redeem(code));
        String brief = registry.issue("0123456789abcdef", 5L, false);
        now.addAndGet(30_000L);
        assertNotNull(registry.redeem(brief), "a link never lives less than a minute");
    }
}
