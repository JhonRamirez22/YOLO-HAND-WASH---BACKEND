package com.handwash.desktop;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MjpegStreamReaderTest {
    @Test
    void readsAdjacentFramesWithoutPersistingOrLosingTheirBoundaries() throws IOException {
        byte[] stream = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n"
            + "ABCD\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\nXYZ")
            .getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream input = new ByteArrayInputStream(stream);

        assertArrayEquals("ABCD".getBytes(StandardCharsets.US_ASCII),
            MjpegStreamReader.readFrame(input, "frame"));
        assertArrayEquals("XYZ".getBytes(StandardCharsets.US_ASCII),
            MjpegStreamReader.readFrame(input, "frame"));
        assertNull(MjpegStreamReader.readFrame(input, "frame"));
    }

    @Test
    void rejectsMissingLengthAndOversizedFramesBeforeAllocatingTheBody() {
        ByteArrayInputStream missingLength = bytes(
            "--frame\r\nContent-Type: image/jpeg\r\n\r\nJPEG");
        assertThrows(IOException.class, () -> MjpegStreamReader.readFrame(missingLength, "frame"));

        ByteArrayInputStream oversized = bytes(
            "--frame\r\nContent-Length: 2097153\r\n\r\n");
        assertThrows(IOException.class, () -> MjpegStreamReader.readFrame(oversized, "frame"));

        ByteArrayInputStream duplicated = bytes(
            "--frame\r\nContent-Length: -1\r\nContent-Length: 4\r\n\r\nJPEG");
        assertThrows(IOException.class, () -> MjpegStreamReader.readFrame(duplicated, "frame"));
    }

    @Test
    void rejectsTruncatedFrameBody() {
        ByteArrayInputStream truncated = bytes(
            "--frame\r\nContent-Length: 5\r\n\r\nAB");
        assertThrows(IOException.class, () -> MjpegStreamReader.readFrame(truncated, "frame"));
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
