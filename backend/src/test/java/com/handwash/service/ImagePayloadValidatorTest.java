package com.handwash.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ImagePayloadValidatorTest {
    private static final long MAX_IMAGE_BYTES = 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000;

    private final ImagePayloadValidator validator =
        new ImagePayloadValidator(MAX_IMAGE_BYTES, MAX_IMAGE_PIXELS);

    @Test
    void validatesHeaderAndDecodesAnImageWithinLimits() throws IOException {
        byte[] image = pngImage(2, 3);

        assertEquals(new ImagePayloadValidator.ImageDimensions(2, 3), validator.validate(image));
        assertEquals(2, validator.decode(image).getWidth());
        assertEquals(3, validator.decode(image).getHeight());
    }

    @Test
    void rejectsCompressedPayloadAboveByteLimitBeforeParsing() {
        assertThrows(ImagePayloadTooLargeException.class,
            () -> validator.validate(new byte[(int) MAX_IMAGE_BYTES + 1]));
    }

    @Test
    void rejectsOversizedPixelHeaderBeforeAllocatingRaster() throws IOException {
        byte[] oversizedHeader = pngWithDimensions(4001, 4000);

        assertThrows(ImagePayloadTooLargeException.class, () -> validator.validate(oversizedHeader));
        assertThrows(ImagePayloadTooLargeException.class, () -> validator.decode(oversizedHeader));
    }

    @Test
    void acceptsHeaderAtExactPixelLimit() throws IOException {
        assertEquals(new ImagePayloadValidator.ImageDimensions(4000, 4000),
            validator.validate(pngWithDimensions(4000, 4000)));
    }

    @Test
    void rejectsUnsupportedOrMalformedImageBytes() {
        assertThrows(InvalidImagePayloadException.class,
            () -> validator.validate(new byte[]{1, 2, 3, 4}));
    }

    private static byte[] pngImage(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** Rewrites only the PNG IHDR dimensions so the header can test limits without a huge raster. */
    private static byte[] pngWithDimensions(int width, int height) throws IOException {
        byte[] png = pngImage(1, 1);
        ByteBuffer.wrap(png).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17); // IHDR type (4 bytes) and payload (13 bytes).
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        return png;
    }
}
