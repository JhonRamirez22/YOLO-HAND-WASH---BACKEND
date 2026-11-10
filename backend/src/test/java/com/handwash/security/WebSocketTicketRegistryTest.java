package com.handwash.security;

import com.handwash.service.SessionManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketTicketRegistryTest {
    @Test
    void ticketIsRandomSingleUseAndBoundToItsSession() {
        AtomicLong monotonic = new AtomicLong(1_000L);
        AtomicLong epoch = new AtomicLong(10_000L);
        WebSocketTicketRegistry registry = new WebSocketTicketRegistry(
            monotonic::get, epoch::get, 30_000L);
        SessionManager.AccessInfo access = ownerAccess();

        var issued = registry.issue("session-a", access);
        assertEquals(43, issued.value().length());
        assertEquals(40_000L, issued.expiresAtEpochMs());
        assertNull(registry.consume("session-b", issued.value()));
        assertNull(registry.consume("session-a", issued.value()),
            "attempting to bind the ticket to another session consumes it");

        var valid = registry.issue("session-a", access);
        assertEquals(access, registry.consume("session-a", valid.value()).accessInfo());
        assertNull(registry.consume("session-a", valid.value()), "ticket replay must fail");
    }

    @Test
    void ticketExpiresByEitherWallOrMonotonicClock() {
        AtomicLong monotonic = new AtomicLong(100L);
        AtomicLong epoch = new AtomicLong(1_000L);
        WebSocketTicketRegistry registry = new WebSocketTicketRegistry(
            monotonic::get, epoch::get, 100L);

        var expiredByMonotonic = registry.issue("session-a", ownerAccess());
        monotonic.addAndGet(100_000_000L);
        assertNull(registry.consume("session-a", expiredByMonotonic.value()));

        var expiredByWall = registry.issue("session-a", ownerAccess());
        epoch.addAndGet(100L);
        assertNull(registry.consume("session-a", expiredByWall.value()));
    }

    @Test
    void ticketLifetimeNeverOutlivesTheCredential() {
        AtomicLong monotonic = new AtomicLong(1_000L);
        AtomicLong epoch = new AtomicLong(10_000L);
        WebSocketTicketRegistry registry = new WebSocketTicketRegistry(
            monotonic::get, epoch::get, 30_000L);
        SessionManager.AccessInfo access = new SessionManager.AccessInfo(
            SessionManager.AccessRole.DEVICE, 10_050L, 80_001_000L, 7L);

        var issued = registry.issue("session-a", access);

        assertEquals(10_050L, issued.expiresAtEpochMs());
        monotonic.addAndGet(50_000_000L);
        assertNull(registry.consume("session-a", issued.value()),
            "credential's monotonic expiry is earlier than its wall-clock expiry");
    }

    @Test
    void doesNotIssueTicketForExpiredCredential() {
        AtomicLong monotonic = new AtomicLong(1_000L);
        AtomicLong epoch = new AtomicLong(10_000L);
        WebSocketTicketRegistry registry = new WebSocketTicketRegistry(
            monotonic::get, epoch::get, 30_000L);
        SessionManager.AccessInfo expired = new SessionManager.AccessInfo(
            SessionManager.AccessRole.OWNER, 10_000L, 1_000L, 7L);

        assertThrows(WebSocketTicketRegistry.TicketExpiredException.class,
            () -> registry.issue("session-a", expired));
    }

    @Test
    void rejectsInvalidTicketTtlAndMissingAccess() {
        assertThrows(IllegalArgumentException.class,
            () -> new WebSocketTicketRegistry(System::nanoTime, System::currentTimeMillis, 30_001L));
        WebSocketTicketRegistry registry = new WebSocketTicketRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.issue("session-a", null));
    }

    private SessionManager.AccessInfo ownerAccess() {
        return new SessionManager.AccessInfo(SessionManager.AccessRole.OWNER,
            1_000_000_000L, 1_000_000_000_000L, 1L);
    }
}
