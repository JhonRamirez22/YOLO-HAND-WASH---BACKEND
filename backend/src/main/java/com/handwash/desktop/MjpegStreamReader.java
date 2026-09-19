package com.handwash.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Reads the existing loopback MJPEG stream without a browser or frame files. */
final class MjpegStreamReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MjpegStreamReader.class);
    private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    private static final int MAX_JPEG_BYTES = 2 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 2_073_600L;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private final Consumer<BufferedImage> onFrame;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "handwash-desktop-mjpeg");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<InputStream> activeStream = new AtomicReference<>();
    private volatile String sessionId;
    private volatile boolean closed;

    MjpegStreamReader(Consumer<BufferedImage> onFrame) {
        this.onFrame = Objects.requireNonNull(onFrame);
    }

    synchronized void connect(String nextSessionId) {
        if (closed || nextSessionId == null || nextSessionId.isBlank()
            || nextSessionId.equals(sessionId)) return;
        sessionId = nextSessionId;
        long run = generation.incrementAndGet();
        closeActiveStream();
        worker.execute(() -> readLoop(nextSessionId, run));
    }

    synchronized void disconnect() {
        sessionId = null;
        generation.incrementAndGet();
        closeActiveStream();
    }

    private void readLoop(String requestedSessionId, long run) {
        while (isCurrent(requestedSessionId, run)) {
            try {
                String encodedSession = URLEncoder.encode(requestedSessionId, StandardCharsets.UTF_8);
                URI uri = URI.create("http://127.0.0.1:8091/video.mjpg?sessionId=" + encodedSession);
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "multipart/x-mixed-replace")
                    .GET()
                    .build();
                HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    activeStream.set(input);
                    if (!isCurrent(requestedSessionId, run)) continue;
                    if (response.statusCode() != 200) {
                        throw new IOException("MJPEG respondió HTTP " + response.statusCode());
                    }
                    String boundary = boundary(response.headers()
                        .firstValue("Content-Type").orElse(""));
                    while (isCurrent(requestedSessionId, run)) {
                        byte[] jpeg = readFrame(input, boundary);
                        if (jpeg == null) break;
                        BufferedImage image = decode(jpeg);
                        if (isCurrent(requestedSessionId, run)) onFrame.accept(image);
                    }
                } finally {
                    activeStream.compareAndSet(response.body(), null);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException error) {
                if (isCurrent(requestedSessionId, run)) {
                    log.debug("Stream local MJPEG no disponible: {}", error.getMessage());
                }
            }
            if (isCurrent(requestedSessionId, run)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean isCurrent(String requestedSessionId, long run) {
        return !closed && generation.get() == run && requestedSessionId.equals(sessionId);
    }

    private void closeActiveStream() {
        InputStream input = activeStream.getAndSet(null);
        if (input != null) {
            try {
                input.close();
            } catch (IOException error) {
                log.debug("No se pudo cerrar el stream MJPEG anterior: {}",
                    error.getClass().getSimpleName());
            }
        }
    }

    private static String boundary(String contentType) throws IOException {
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("multipart/x-mixed-replace")) {
            throw new IOException("Content-Type MJPEG inválido");
        }
        for (String parameter : contentType.split(";")) {
            String item = parameter.trim();
            if (item.regionMatches(true, 0, "boundary=", 0, 9)) {
                String value = item.substring(9).trim().replaceAll("^\"|\"$", "");
                if (value.matches("[A-Za-z0-9_-]{1,70}")) return value;
            }
        }
        throw new IOException("El stream MJPEG no declaró un boundary válido");
    }

    static byte[] readFrame(InputStream input, String boundary) throws IOException {
        String boundaryLine = "--" + boundary;
        String line;
        do {
            line = readAsciiLine(input);
            if (line == null) return null;
        } while (!line.equals(boundaryLine));

        int contentLength = -1;
        boolean contentLengthSeen = false;
        int headerBytes = 0;
        while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_LINE_BYTES) {
                throw new IOException("Cabeceras MJPEG exceden el límite");
            }
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                if (contentLengthSeen) throw new IOException("Content-Length MJPEG duplicado");
                contentLengthSeen = true;
                try {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException invalidLength) {
                    throw new IOException("Content-Length MJPEG inválido", invalidLength);
                }
            }
        }
        if (line == null) throw new EOFException("Cabecera MJPEG incompleta");
        if (!contentLengthSeen || contentLength < 1 || contentLength > MAX_JPEG_BYTES) {
            throw new IOException("Tamaño de frame MJPEG fuera del límite");
        }
        byte[] bytes = input.readNBytes(contentLength);
        if (bytes.length != contentLength) throw new EOFException("Frame MJPEG truncado");
        return bytes;
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (line.size() <= MAX_HEADER_LINE_BYTES) {
            int value = input.read();
            if (value < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
            if (value == '\n') return line.toString(StandardCharsets.US_ASCII);
            if (value != '\r') line.write(value);
        }
        throw new IOException("Línea de cabecera MJPEG excede el límite");
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        try (MemoryCacheImageInputStream imageInput =
                 new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IOException("Frame JPEG inválido");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("Resolución del frame MJPEG fuera del límite");
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("No se pudo decodificar el frame JPEG");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnect();
        worker.shutdownNow();
    }
}
