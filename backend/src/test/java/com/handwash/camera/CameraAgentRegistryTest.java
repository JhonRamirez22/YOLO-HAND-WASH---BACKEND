package com.handwash.camera;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CameraAgentRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final CameraAgentRegistry registry = new CameraAgentRegistry(now::get);

    @Test
    void anUnseenStationHasNoAgentAndNothingRequested() {
        var view = registry.view("station-1");
        assertFalse(view.online());
        assertFalse(view.wanted());
        assertEquals("none", view.state());
    }

    @Test
    void theAgentReportsItselfAndTheConsoleSeesItOnlineWithItsState() {
        registry.heartbeat("station-1", "Cámara de iPhone De Jhon", "ready", "iPhone listo");

        var view = registry.view("station-1");
        assertTrue(view.online());
        assertEquals("ready", view.state());
        assertEquals("Cámara de iPhone De Jhon", view.device());
        assertEquals("iPhone listo", view.message());
    }

    @Test
    void anAgentThatStopsReportingIsOfflineAfterTenSeconds() {
        registry.heartbeat("station-1", "iPhone", "ready", "ok");
        now.addAndGet(9_000L);
        assertTrue(registry.view("station-1").online());
        now.addAndGet(2_000L);
        var view = registry.view("station-1");
        assertFalse(view.online());
        assertEquals("offline", view.state());
    }

    @Test
    void theConsoleRequestReachesTheAgentOnItsNextHeartbeatAndCanBeWithdrawn() {
        assertFalse(registry.heartbeat("station-1", "iPhone", "ready", "ok"));
        registry.request("station-1", true);
        assertTrue(registry.view("station-1").wanted());
        assertTrue(registry.heartbeat("station-1", "iPhone", "ready", "ok"));
        registry.request("station-1", false);
        assertFalse(registry.heartbeat("station-1", "iPhone", "streaming", "enviando"));
    }

    @Test
    void aRequestExpiresIfNobodyRenewsItSoALostConsoleDoesNotKeepTheCameraOnForever() {
        registry.request("station-1", true);
        now.addAndGet(CameraAgentRegistry.REQUEST_TTL_MS + 1);
        assertFalse(registry.view("station-1").wanted());
        registry.request("station-1", true);
        now.addAndGet(CameraAgentRegistry.REQUEST_TTL_MS - 1_000L);
        registry.request("station-1", true); // the console keeps renewing while it is open
        now.addAndGet(CameraAgentRegistry.REQUEST_TTL_MS - 1_000L);
        assertTrue(registry.view("station-1").wanted());
    }

    @Test
    void textIsBoundedAndCleanedAndTheStateIsFromAKnownSet() {
        registry.heartbeat("station-1", "x".repeat(500) + "<b>", "hacked", "line1\nline2 <script>");
        var view = registry.view("station-1");
        assertTrue(view.device().length() <= 80);
        assertFalse(view.device().contains("<"));
        assertFalse(view.message().contains("<") || view.message().contains("\n"));
        assertEquals("error", view.state(), "an unknown state is shown as an error, never as ready");
    }

    @Test
    void stationsDoNotSeeEachOther() {
        registry.heartbeat("station-1", "iPhone", "ready", "ok");
        registry.request("station-1", true);
        assertFalse(registry.view("station-2").online());
        assertFalse(registry.view("station-2").wanted());
    }

    @Test
    void theNumberOfTrackedStationsIsBoundedAndTheOverflowIsRefused() {
        for (int i = 0; i < CameraAgentRegistry.MAX_STATIONS; i++) registry.heartbeat("s" + i, "d", "ready", "m");
        assertThrows(IllegalStateException.class, () -> registry.heartbeat("one-more", "d", "ready", "m"));
        assertEquals(CameraAgentRegistry.MAX_STATIONS, registry.trackedStations());
        now.addAndGet(CameraAgentRegistry.ONLINE_MS * 61);
        registry.heartbeat("one-more", "d", "ready", "m"); // stale entries are reclaimed
        assertTrue(registry.trackedStations() < CameraAgentRegistry.MAX_STATIONS);
    }
}
