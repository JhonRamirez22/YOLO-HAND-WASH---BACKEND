package com.handwash.preview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CameraFrameHubTest {
    /** A framed camera image: 4 bytes of sequence, then a JPEG. */
    private static byte[] frame(int seq, int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) (seq >>> 24); bytes[1] = (byte) (seq >>> 16); bytes[2] = (byte) (seq >>> 8); bytes[3] = (byte) seq;
        bytes[4] = (byte) 0xFF; bytes[5] = (byte) 0xD8;
        return bytes;
    }

    private static final class Collector implements CameraFrameHub.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final List<String> overlays = new ArrayList<>();
        @Override public void binary(byte[] data) { frames.add(data); }
        @Override public void text(String json) { overlays.add(json); }
    }

    @Test
    void aFrameReachesTheProducerAndEveryViewerOfItsSessionOnly() {
        var hub = new CameraFrameHub();
        var pc = new Collector(); var panel = new Collector(); var other = new Collector();
        hub.subscribe("s1", CameraFrameHub.Role.PRODUCER, pc);
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, panel);
        hub.subscribe("s2", CameraFrameHub.Role.VIEWER, other);

        assertTrue(hub.publishFrame("s1", frame(7, 100)));

        assertEquals(1, pc.frames.size());
        assertEquals(1, panel.frames.size());
        assertTrue(other.frames.isEmpty());
    }

    @Test
    void overlaysFromThePcGoToTheViewersAndNeverBackToTheProducer() {
        var hub = new CameraFrameHub();
        var pc = new Collector(); var panel = new Collector();
        hub.subscribe("s1", CameraFrameHub.Role.PRODUCER, pc);
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, panel);

        assertTrue(hub.publishOverlay("s1", "{\"seq\":7,\"hands\":[]}"));

        assertEquals(List.of("{\"seq\":7,\"hands\":[]}"), panel.overlays);
        assertTrue(pc.overlays.isEmpty());
    }

    @Test
    void framesThatAreNotFramedJpegsOrAreTooBigAreRefused() {
        var hub = new CameraFrameHub();
        var panel = new Collector();
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, panel);
        assertFalse(hub.publishFrame("s1", new byte[] {1, 2, 3, 4, 5, 6}));
        assertFalse(hub.publishFrame("s1", new byte[4]));
        assertFalse(hub.publishFrame("s1", frame(1, CameraFrameHub.MAX_FRAME_BYTES + 1)));
        assertFalse(hub.publishFrame("s1", null));
        assertFalse(hub.publishOverlay("s1", "x".repeat(CameraFrameHub.MAX_OVERLAY_CHARS + 1)));
        assertFalse(hub.publishOverlay("s1", "not json"));
        assertTrue(panel.frames.isEmpty() && panel.overlays.isEmpty());
    }

    @Test
    void anUnsubscribedSinkStopsReceivingAndAFailingSinkDoesNotStopTheOthers() {
        var hub = new CameraFrameHub();
        var good = new Collector(); var gone = new Collector();
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, new CameraFrameHub.Sink() {
            @Override public void binary(byte[] data) { throw new IllegalStateException("slow socket"); }
            @Override public void text(String json) { throw new IllegalStateException("slow socket"); }
        });
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, good);
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, gone).run();

        hub.publishFrame("s1", frame(1, 50));

        assertEquals(1, good.frames.size());
        assertTrue(gone.frames.isEmpty());
        assertEquals(2, hub.count("s1", CameraFrameHub.Role.VIEWER));
    }

    @Test
    void thereIsOneProducerAndABoundedNumberOfViewers() {
        var hub = new CameraFrameHub();
        hub.subscribe("s1", CameraFrameHub.Role.PRODUCER, new Collector());
        assertThrows(IllegalStateException.class, () -> hub.subscribe("s1", CameraFrameHub.Role.PRODUCER, new Collector()));
        for (int i = 0; i < CameraFrameHub.MAX_VIEWERS; i++) hub.subscribe("s1", CameraFrameHub.Role.VIEWER, new Collector());
        assertThrows(IllegalStateException.class, () -> hub.subscribe("s1", CameraFrameHub.Role.VIEWER, new Collector()));
    }

    @Test
    void closingASessionDropsEverySubscriber() {
        var hub = new CameraFrameHub();
        hub.subscribe("s1", CameraFrameHub.Role.PRODUCER, new Collector());
        hub.subscribe("s1", CameraFrameHub.Role.VIEWER, new Collector());
        hub.close("s1");
        assertEquals(0, hub.count("s1", CameraFrameHub.Role.PRODUCER) + hub.count("s1", CameraFrameHub.Role.VIEWER));
    }
}
