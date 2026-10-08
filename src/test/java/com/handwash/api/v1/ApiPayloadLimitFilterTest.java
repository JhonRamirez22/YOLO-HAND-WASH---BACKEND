package com.handwash.api.v1;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApiPayloadLimitFilterTest {
    private final ApiPayloadLimitFilter filter = new ApiPayloadLimitFilter();

    @Test
    void rejectsDeclaredOversizedDetectionBodyBeforeReadingOrDispatching() throws Exception {
        MockHttpServletRequest request = request("/api/v1/deteccion");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();
        var declaredOversize = new HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() {
                return ApiPayloadLimitFilter.MAX_DETECTION_BODY_BYTES + 1L;
            }
        };

        filter.doFilter(declaredOversize, response, chain(dispatched));

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("CARGA_EXCEDE_LIMITE"));
        assertFalse(dispatched.get());
    }

    @Test
    void boundsUnknownLengthDetectionBodyAndRejectsItWhenItExceedsLimit() throws Exception {
        MockHttpServletRequest request = request("/api/v1/deteccion");
        request.setContent(new byte[ApiPayloadLimitFilter.MAX_DETECTION_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();

        filter.doFilter(request, response, chain(dispatched));

        assertEquals(413, response.getStatus());
        assertFalse(dispatched.get());
    }

    @Test
    void replaysUnknownLengthBodyExactlyOnceWhenWithinLimit() throws Exception {
        byte[] expected = "{\"sessionId\":\"demo\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request("/api/v1/deteccion");
        request.setContent(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ServletRequest wrapped, ServletResponse ignored) -> {
            assertEquals(expected.length, wrapped.getContentLength());
            observed.set(new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertEquals(new String(expected, StandardCharsets.UTF_8), observed.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void preservesZeroCopyFastPathForKnownSmallDetectionBody() throws Exception {
        MockHttpServletRequest request = request("/api/v1/deteccion");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var knownLengthRequest = new HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() { return 128L; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("No debe leer ni almacenar en búfer el cuerpo con longitud conocida");
            }
        };
        AtomicBoolean dispatched = new AtomicBoolean();

        filter.doFilter(knownLengthRequest, response, (wrapped, ignored) -> {
            assertSame(knownLengthRequest, wrapped);
            dispatched.set(true);
        });

        assertTrue(dispatched.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void usesLargerBoundForOtherJsonApiRequests() throws Exception {
        MockHttpServletRequest request = request("/api/v1/auth/login");
        request.setContent(new byte[ApiPayloadLimitFilter.MAX_API_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();

        filter.doFilter(request, response, chain(dispatched));

        assertEquals(413, response.getStatus());
        assertFalse(dispatched.get());
    }

    @Test
    void leavesMultipartInferenceUploadToItsDedicatedSizeValidator() throws Exception {
        MockHttpServletRequest request = request("/api/infer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();
        var requestWithLargeDeclaredBody = new HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() {
                return ApiPayloadLimitFilter.MAX_API_BODY_BYTES + 1L;
            }
        };

        filter.doFilter(requestWithLargeDeclaredBody, response, chain(dispatched));

        assertTrue(dispatched.get());
        assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setServletPath(path);
        return request;
    }

    private static FilterChain chain(AtomicBoolean dispatched) {
        return (request, response) -> dispatched.set(true);
    }
}
