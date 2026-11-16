package com.handwash.service.evidence;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.handwash.service.evidence.EvidenceTestSupport.jpeg;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSanitizerTest {
    private static final int MAX = EvidenceProperties.MAX_UPLOAD_BYTES;

    @Test
    void reEncodesARealJpegAndCapsItsWidth() throws Exception {
        byte[] output = ImageSanitizer.sanitize(jpeg(1600, 1200), 2_000_000);

        var image = ImageIO.read(new ByteArrayInputStream(output));
        assertEquals(960, image.getWidth());
        assertEquals(720, image.getHeight());
        assertEquals(0xFF, output[0] & 0xFF);
        assertEquals(0xD8, output[1] & 0xFF);
    }

    @Test
    void keepsSmallImagesAtTheirSize() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(ImageSanitizer.sanitize(jpeg(640, 480), MAX)));
        assertEquals(640, image.getWidth());
        assertEquals(480, image.getHeight());
    }

    @Test
    void dropsDataAppendedAfterTheImage() {
        byte[] original = jpeg(640, 480);
        byte[] trailer = "SECRET-TRAILER-DATA".getBytes(StandardCharsets.UTF_8);
        byte[] polluted = Arrays.copyOf(original, original.length + trailer.length);
        System.arraycopy(trailer, 0, polluted, original.length, trailer.length);

        byte[] output = ImageSanitizer.sanitize(polluted, MAX);

        assertFalse(new String(output, StandardCharsets.ISO_8859_1).contains("SECRET-TRAILER-DATA"));
    }

    @Test
    void rejectsAnythingThatIsNotAnAcceptableJpeg() {
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(null, MAX));
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(new byte[100], MAX), "too small");
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(jpeg(640, 480), 1_000), "over the byte limit");
        byte[] png = new byte[4_000];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(png, MAX), "not a JPEG");
        byte[] fake = new byte[4_000];
        fake[0] = (byte) 0xFF; fake[1] = (byte) 0xD8; fake[2] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(fake, MAX), "JPEG header with garbage");
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(jpeg(32, 32), MAX), "too few pixels");
    }

    @Test
    void rejectsDeclaredDimensionsOverTheLimitWithoutDecodingThem() {
        byte[] huge = jpeg(2_600, 2_600);
        assertTrue(huge.length > 2_000);
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.sanitize(huge, 5_000_000));
    }
}
