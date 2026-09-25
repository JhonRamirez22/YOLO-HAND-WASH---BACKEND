package com.handwash.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Validates compressed image limits before a decoder allocates the pixel raster. */
@Component
public final class ImagePayloadValidator {
    private final long maxImageBytes;
    private final long maxImagePixels;

    public ImagePayloadValidator(
        @Value("${handwash.inference.max-image-bytes:8388608}") long maxImageBytes,
        @Value("${handwash.inference.max-image-pixels:16000000}") long maxImagePixels
    ) {
        if (maxImageBytes <= 0 || maxImagePixels <= 0) {
            throw new IllegalArgumentException("Image byte and pixel limits must be positive");
        }
        this.maxImageBytes = maxImageBytes;
        this.maxImagePixels = maxImagePixels;
    }

    /** Reads only the image header; it does not decode or allocate the full raster. */
    public ImageDimensions validate(byte[] bytes) throws IOException {
        validateByteArray(bytes);
        return withReader(bytes, reader -> validatedDimensions(reader));
    }

    /** Decodes only after validating dimensions from the image header. */
    public BufferedImage decode(byte[] bytes) throws IOException {
        validateByteArray(bytes);
        return withReader(bytes, reader -> {
            validatedDimensions(reader);
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new InvalidImagePayloadException("Image decoder returned no raster");
            }
            return image;
        });
    }

    public void validateFileSize(long sizeBytes) throws ImagePayloadTooLargeException {
        if (sizeBytes > maxImageBytes) {
            throw new ImagePayloadTooLargeException("Image exceeds configured byte limit");
        }
    }

    public long maxImageBytes() {
        return maxImageBytes;
    }

    public long maxImagePixels() {
        return maxImagePixels;
    }

    private void validateByteArray(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidImagePayloadException("Image payload is empty");
        }
        validateFileSize(bytes.length);
    }

    private ImageDimensions validatedDimensions(ImageReader reader) throws IOException {
        final int width;
        final int height;
        try {
            width = reader.getWidth(0);
            height = reader.getHeight(0);
        } catch (IndexOutOfBoundsException malformed) {
            throw new InvalidImagePayloadException("Image has no first frame", malformed);
        }
        if (width <= 0 || height <= 0) {
            throw new InvalidImagePayloadException("Image dimensions must be positive");
        }

        long pixels = (long) width * height;
        if (pixels > maxImagePixels) {
            throw new ImagePayloadTooLargeException("Image exceeds configured pixel limit");
        }
        return new ImageDimensions(width, height);
    }

    private <T> T withReader(byte[] bytes, ReaderOperation<T> operation) throws IOException {
        // Explicit memory-backed stream: ImageIO's default cache may use temporary files.
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImagePayloadException("Unsupported or invalid image payload");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return operation.apply(reader);
            } finally {
                reader.dispose();
            }
        }
    }

    @FunctionalInterface
    private interface ReaderOperation<T> {
        T apply(ImageReader reader) throws IOException;
    }

    public record ImageDimensions(int width, int height) {}
}
