package com.handwash.preview;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mediator between the phone camera, the RTX PC and the panel. The phone publishes JPEG frames (each prefixed with a
 * 4-byte sequence number); the hub hands every frame to the PC (to detect) and to the panels (to watch it in full
 * quality). The PC answers with a small overlay (hand boxes and the detected step) that only the panels receive.
 * Nothing is stored; a slow subscriber is its own problem and never delays the others.
 */
@Component
public class CameraFrameHub {
    public enum Role { PRODUCER, VIEWER }

    /** Receives what the hub forwards; must not block. */
    public interface Sink {
        void binary(byte[] data);
        void text(String json);
    }

    static final int MAX_FRAME_BYTES = 512 * 1024;
    static final int MAX_OVERLAY_CHARS = 16 * 1024;
    static final int MAX_VIEWERS = 4;
    private static final int SEQUENCE_BYTES = 4;

    private final Map<String, List<Sink>> producers = new ConcurrentHashMap<>();
    private final Map<String, List<Sink>> viewers = new ConcurrentHashMap<>();

    /** @return a handle that unsubscribes. */
    public Runnable subscribe(String sessionId, Role role, Sink sink) {
        Map<String, List<Sink>> map = role == Role.PRODUCER ? producers : viewers;
        List<Sink> sinks = map.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>());
        synchronized (sinks) {
            int limit = role == Role.PRODUCER ? 1 : MAX_VIEWERS;
            if (sinks.size() >= limit) throw new IllegalStateException("Demasiados suscriptores " + role);
            sinks.add(sink);
        }
        return () -> sinks.remove(sink);
    }

    /** @return false when the frame is not a sequence number followed by a JPEG, or is too big. */
    public boolean publishFrame(String sessionId, byte[] framed) {
        if (framed == null || framed.length < SEQUENCE_BYTES + 2 || framed.length > MAX_FRAME_BYTES
            || (framed[SEQUENCE_BYTES] & 0xFF) != 0xFF || (framed[SEQUENCE_BYTES + 1] & 0xFF) != 0xD8) return false;
        deliver(producers.get(sessionId), sink -> sink.binary(framed));
        deliver(viewers.get(sessionId), sink -> sink.binary(framed));
        return true;
    }

    /** @return false when the overlay is not a small JSON object. */
    public boolean publishOverlay(String sessionId, String json) {
        if (json == null || json.length() > MAX_OVERLAY_CHARS || !json.startsWith("{")) return false;
        deliver(viewers.get(sessionId), sink -> sink.text(json));
        return true;
    }

    /** Java's progress for the PC, so the classifier concentrates on the step that should come next. */
    public void publishHint(String sessionId, String json) {
        deliver(producers.get(sessionId), sink -> sink.text(json));
    }

    public int count(String sessionId, Role role) {
        List<Sink> sinks = (role == Role.PRODUCER ? producers : viewers).get(sessionId);
        return sinks == null ? 0 : sinks.size();
    }

    public void close(String sessionId) {
        producers.remove(sessionId);
        viewers.remove(sessionId);
    }

    private static void deliver(List<Sink> sinks, java.util.function.Consumer<Sink> action) {
        if (sinks == null) return;
        for (Sink sink : sinks) {
            try { action.accept(sink); } catch (RuntimeException failing) { /* one bad subscriber never stops the rest */ }
        }
    }
}
