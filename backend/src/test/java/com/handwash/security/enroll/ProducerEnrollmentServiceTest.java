package com.handwash.security.enroll;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ProducerEnrollmentServiceTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ProducerEnrollmentService service = new ProducerEnrollmentService(now::get);
    private static final String KEY = "hwk_0123456789abcdef_" + "A".repeat(43);

    @Test
    void aPcAnnouncesItselfAndIsListedForTheAdminWithAShortCode() {
        var started = service.start("DESKTOP-RTX", "203.0.113.5");

        assertTrue(started.userCode().matches("[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertTrue(started.deviceCode().length() >= 40);
        assertEquals(600, started.expiresInSeconds());
        var pending = service.pending();
        assertEquals(1, pending.size());
        assertEquals(started.userCode(), pending.get(0).userCode());
        assertEquals("DESKTOP-RTX", pending.get(0).deviceName());
        assertEquals("pending", service.poll(started.deviceCode()).status());
    }

    @Test
    void approvalHandsTheIssuedKeyToTheWaitingPcExactlyOnce() {
        var started = service.start("DESKTOP-RTX", "203.0.113.5");

        assertTrue(service.approve(started.userCode(), (name) -> KEY));

        var first = service.poll(started.deviceCode());
        assertEquals("approved", first.status());
        assertEquals(KEY, first.key());
        assertEquals("expired", service.poll(started.deviceCode()).status());
        assertTrue(service.pending().isEmpty());
    }

    @Test
    void anUnknownOrExpiredRequestGetsNothing() {
        assertEquals("expired", service.poll("x".repeat(43)).status());
        var started = service.start("PC", "203.0.113.5");
        now.addAndGet(601_000L);

        assertEquals("expired", service.poll(started.deviceCode()).status());
        assertFalse(service.approve(started.userCode(), (name) -> KEY));
        assertTrue(service.pending().isEmpty());
    }

    @Test
    void theKeyIsNeverIssuedWhenThereIsNothingToApprove() {
        boolean[] called = {false};
        assertFalse(service.approve("ABCD-2345", (name) -> { called[0] = true; return KEY; }));
        assertFalse(called[0]);
    }

    @Test
    void aSingleAddressCannotFloodThePendingList() {
        for (int i = 0; i < 3; i++) service.start("PC" + i, "198.51.100.9");
        assertThrows(IllegalStateException.class, () -> service.start("PC3", "198.51.100.9"));
        service.start("OTRO", "198.51.100.10");
        for (int i = 0; i < 16; i++) service.start("X" + i, "10.0.0." + i);
        assertThrows(IllegalStateException.class, () -> service.start("Y", "10.9.9.9"));
    }

    @Test
    void deviceNamesAreBoundedAndCleaned() {
        var started = service.start("  <b>PC</b>\nmuy-" + "x".repeat(200), "203.0.113.5");
        String name = service.pending().get(0).deviceName();
        assertTrue(name.length() <= 40);
        assertFalse(name.contains("<") || name.contains("\n"));
        assertNotNull(started);
        assertThrows(IllegalArgumentException.class, () -> service.start("   ", "203.0.113.5"));
    }

    @Test
    void theApprovedUserCodeMatchesCaseInsensitivelyAndTheRejectionDeletesTheRequest() {
        var started = service.start("PC", "203.0.113.5");
        assertTrue(service.reject(started.userCode().toLowerCase()));
        assertEquals("expired", service.poll(started.deviceCode()).status());
    }
}
