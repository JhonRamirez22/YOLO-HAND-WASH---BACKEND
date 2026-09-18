package com.handwash.camera;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory link between a station's console and the camera helper running on its Mac: the helper reports every
 * couple of seconds and learns whether the console wants the iPhone camera streamed; the console sees whether the
 * helper is alive. Only short status text lives here: no video, no credentials, nothing persisted.
 */
@Component
public class CameraAgentRegistry {
    static final long ONLINE_MS = 10_000L;
    static final long REQUEST_TTL_MS = 30_000L;
    static final int MAX_STATIONS = 200;
    private static final Set<String> STATES = Set.of("ready", "streaming", "waiting", "no-iphone", "error");

    public record View(boolean online, boolean wanted, String state, String device, String message) {}

    private static final class Entry {
        volatile long lastSeen;
        volatile long requestedAt;
        volatile String state = "none";
        volatile String device = "";
        volatile String message = "";
    }

    private final Map<String, Entry> byStation = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CameraAgentRegistry() { this(System::currentTimeMillis); }

    CameraAgentRegistry(LongSupplier clock) { this.clock = clock; }

    /** Called by the Mac helper; returns whether the console currently wants the camera streamed. */
    public boolean heartbeat(String stationId, String device, String state, String message) {
        Entry entry = entry(stationId);
        entry.lastSeen = clock.getAsLong();
        entry.state = STATES.contains(state) ? state : "error";
        entry.device = clean(device, 80);
        entry.message = clean(message, 160);
        return wanted(entry);
    }

    /** Called by the console: true asks for the helper to stream (and must be renewed); false withdraws it. */
    public void request(String stationId, boolean wanted) {
        entry(stationId).requestedAt = wanted ? clock.getAsLong() : 0L;
    }

    public View view(String stationId) {
        Entry entry = byStation.get(stationId);
        if (entry == null) return new View(false, false, "none", "", "");
        boolean online = entry.lastSeen > 0 && clock.getAsLong() - entry.lastSeen <= ONLINE_MS;
        return new View(online, wanted(entry), online ? entry.state : entry.lastSeen > 0 ? "offline" : "none",
            entry.device, entry.message);
    }

    int trackedStations() { return byStation.size(); }

    private boolean wanted(Entry entry) {
        return entry.requestedAt > 0 && clock.getAsLong() - entry.requestedAt <= REQUEST_TTL_MS;
    }

    private Entry entry(String stationId) {
        if (byStation.size() >= MAX_STATIONS && !byStation.containsKey(stationId)) {
            long now = clock.getAsLong();
            byStation.values().removeIf(item -> now - Math.max(item.lastSeen, item.requestedAt) > 60 * ONLINE_MS);
            if (byStation.size() >= MAX_STATIONS) throw new IllegalStateException("Demasiadas estaciones con ayudante");
        }
        return byStation.computeIfAbsent(stationId, ignored -> new Entry());
    }

    private static String clean(String raw, int max) {
        if (raw == null) return "";
        String text = raw.replaceAll("[\\p{Cntrl}<>\"'`]", " ").replaceAll("\\s+", " ").trim();
        return text.length() > max ? text.substring(0, max).trim() : text;
    }
}
