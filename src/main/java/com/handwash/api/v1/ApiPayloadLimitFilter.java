package com.handwash.api.v1;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bounds API bodies before Spring/Jackson allocates request DTOs. */
@Component
public final class ApiPayloadLimitFilter extends OncePerRequestFilter {
    static final int MAX_API_BODY_BYTES = 64 * 1024;
    static final int MAX_DETECTION_BODY_BYTES = 16 * 1024;
    private static final Set<String> DETECTION_PATHS = Set.of(
        "/api/v1/deteccion", "/api/deteccion");
    private static final Set<String> MULTIPART_INFERENCE_PATHS = Set.of(
        "/api/v1/infer", "/api/infer");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return bodyLimit(request) == 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        int maxBodyBytes = bodyLimit(request);
        if (maxBodyBytes == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            reject(response, maxBodyBytes);
            return;
        }
        // The camera producer sends Content-Length. Keep the hot path zero-copy;
        // only unknown-length/chunked requests need bounded buffering.
        if (declaredLength >= 0L) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = readBounded(request.getInputStream(), maxBodyBytes);
        if (body == null) {
            reject(response, maxBodyBytes);
            return;
        }
        filterChain.doFilter(new ReplayableBodyRequest(request, body), response);
    }

    /** Zero means this request is outside the bounded JSON/API-body surface. */
    private static int bodyLimit(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
            && !"PATCH".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return 0;
        }
        String path = request.getServletPath();
        if (MULTIPART_INFERENCE_PATHS.contains(path) || path == null || !path.startsWith("/api/")) {
            return 0;
        }
        return DETECTION_PATHS.contains(path) ? MAX_DETECTION_BODY_BYTES : MAX_API_BODY_BYTES;
    }

    private static byte[] readBounded(ServletInputStream input, int maxBodyBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(1_024);
        byte[] buffer = new byte[4_096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            if (body.size() + bytesRead > maxBodyBytes) return null;
            body.write(buffer, 0, bytesRead);
        }
        return body.toByteArray();
    }

    private static void reject(HttpServletResponse response, int maxBodyBytes) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":\"CARGA_EXCEDE_LIMITE\","
                + "\"mensaje\":\"El cuerpo de la solicitud supera "
                + maxBodyBytes + " bytes.\"}");
    }

    private static final class ReplayableBodyRequest extends HttpServletRequestWrapper {
        private final ReplayableServletInputStream input;
        private boolean inputStreamObtained;
        private boolean readerObtained;

        private ReplayableBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.input = new ReplayableServletInputStream(body);
        }

        @Override
        public ServletInputStream getInputStream() {
            if (readerObtained) throw new IllegalStateException("getReader() ya fue invocado");
            inputStreamObtained = true;
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (inputStreamObtained) throw new IllegalStateException("getInputStream() ya fue invocado");
            readerObtained = true;
            String encoding = getCharacterEncoding();
            if (encoding == null) encoding = StandardCharsets.UTF_8.name();
            return new BufferedReader(new InputStreamReader(input, encoding));
        }

        @Override public int getContentLength() { return input.length(); }
        @Override public long getContentLengthLong() { return input.length(); }
    }

    private static final class ReplayableServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;
        private final int length;
        private ReadListener listener;

        private ReplayableServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
            this.length = body.length;
        }

        @Override public int read() throws IOException { return input.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            return input.read(bytes, offset, length);
        }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        private int length() { return length; }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null || listener != null) {
                throw new IllegalStateException("ReadListener inválido para el cuerpo almacenado");
            }
            listener = readListener;
            try {
                if (!isFinished()) listener.onDataAvailable();
                if (isFinished()) listener.onAllDataRead();
            } catch (IOException error) {
                listener.onError(error);
            }
        }
    }
}
