package com.handwash.service.evidence;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Accepts only a real, modest JPEG and re-encodes it: this drops EXIF and any appended data,
 * rejects decompression bombs by checking the declared size first, and caps the stored width.
 */
public final class ImageSanitizer {
    static final int MIN_BYTES = 2_000;
    static final int MIN_SIDE = 64;
    static final int MAX_SIDE = 2_000;
    static final int OUTPUT_MAX_WIDTH = 960;
    private static final float QUALITY = 0.8f;

    private ImageSanitizer() {}

    /** @throws IllegalArgumentException when the bytes are not an acceptable JPEG */
    public static byte[] sanitize(byte[] input, int maxBytes) {
        if (input == null || input.length < MIN_BYTES || input.length > maxBytes) {
            throw new IllegalArgumentException("Tamaño de imagen fuera de rango");
        }
        if ((input[0] & 0xFF) != 0xFF || (input[1] & 0xFF) != 0xD8 || (input[2] & 0xFF) != 0xFF) {
            throw new IllegalArgumentException("La imagen no es JPEG");
        }
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
            if (stream == null || !readers.hasNext()) throw new IllegalArgumentException("JPEG no soportado");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_SIDE || height > MAX_SIDE) {
                    throw new IllegalArgumentException("Dimensiones de imagen fuera de rango");
                }
                return encode(scaled(reader.read(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("La imagen no se pudo leer", unreadable);
        }
    }

    private static BufferedImage scaled(BufferedImage source) {
        int width = Math.min(source.getWidth(), OUTPUT_MAX_WIDTH);
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(QUALITY);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), params);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
