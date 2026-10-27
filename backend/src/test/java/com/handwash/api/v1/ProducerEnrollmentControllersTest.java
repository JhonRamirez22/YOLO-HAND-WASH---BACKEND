package com.handwash.api.v1;

import com.handwash.controller.ProducerEnrollmentController;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.enroll.ProducerEnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProducerEnrollmentControllersTest {
    private static final String KEY = "hwk_0123456789abcdef_" + "A".repeat(43);
    private final ProducerEnrollmentService enrollments = new ProducerEnrollmentService();
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final ProducerEnrollmentController pc = new ProducerEnrollmentController(enrollments);
    private final AdminProducerEnrollmentController admin = new AdminProducerEnrollmentController(enrollments, keys);
    private final Jwt adminJwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60),
        Map.of("alg", "none"), Map.of("sub", "admin:1"));

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePcAnnouncesItselfTheAdminApprovesAndThePcReceivesItsKeyOnce() {
        when(keys.issue(eq("station-1"), eq("DESKTOP-RTX"), eq(EnumSet.of(ApiKeyScope.PRODUCER)), eq("admin:1")))
            .thenReturn(new ApiKeyService.IssuedKey("0123456789abcdef", KEY));

        var started = (Map<String, Object>) pc.start(new ProducerEnrollmentController.StartRequest("DESKTOP-RTX"),
            from("203.0.113.5")).getBody();
        String userCode = (String) started.get("userCode");
        String deviceCode = (String) started.get("deviceCode");
        var poll = new ProducerEnrollmentController.PollRequest(deviceCode);
        assertEquals("pending", ((Map<String, Object>) pc.poll(poll).getBody()).get("status"));

        assertEquals(1, ((java.util.List<?>) admin.list(adminJwt).getBody()).size());
        assertEquals(204, admin.approve(adminJwt, userCode,
            new AdminProducerEnrollmentController.ApproveRequest("station-1")).getStatusCode().value());

        var approved = (Map<String, Object>) pc.poll(poll).getBody();
        assertEquals("approved", approved.get("status"));
        assertEquals(KEY, approved.get("key"));
        assertEquals("expired", ((Map<String, Object>) pc.poll(poll).getBody()).get("status"));
    }

    @Test
    void invalidRequestsAndMissingAdminAreRefused() {
        assertEquals(400, pc.start(new ProducerEnrollmentController.StartRequest("  "), from("203.0.113.5")).getStatusCode().value());
        assertEquals(400, pc.start(null, from("203.0.113.5")).getStatusCode().value());
        assertEquals(401, admin.list(null).getStatusCode().value());
        assertEquals(401, admin.approve(null, "ABCD-2345", new AdminProducerEnrollmentController.ApproveRequest("s")).getStatusCode().value());
        assertEquals(404, admin.approve(adminJwt, "ABCD-2345", new AdminProducerEnrollmentController.ApproveRequest("s")).getStatusCode().value());
        assertEquals(400, admin.approve(adminJwt, "ABCD-2345", null).getStatusCode().value());
        verifyNoInteractions(keys);
    }

    @Test
    void aFloodFromOneAddressGetsTooManyRequests() {
        for (int i = 0; i < 3; i++) pc.start(new ProducerEnrollmentController.StartRequest("PC" + i), from("198.51.100.9"));
        assertEquals(429, pc.start(new ProducerEnrollmentController.StartRequest("PC3"), from("198.51.100.9")).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedKeyIssueLeavesTheRequestPendingForARetry() {
        when(keys.issue(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("puesto inexistente"));
        var started = (Map<String, Object>) pc.start(new ProducerEnrollmentController.StartRequest("PC"), from("203.0.113.5")).getBody();

        assertEquals(400, admin.approve(adminJwt, (String) started.get("userCode"),
            new AdminProducerEnrollmentController.ApproveRequest("nope")).getStatusCode().value());
        assertEquals(1, ((java.util.List<?>) admin.list(adminJwt).getBody()).size());
    }
}
