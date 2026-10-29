package com.handwash.config;

import com.handwash.config.EndpointDiscovery.Endpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * ASVS V4 (access control): the expected policy of EVERY endpoint the cloud profile exposes is written down
 * here, independently of {@link CloudSecurityConfig}, and checked over HTTP for five kinds of caller. A new
 * endpoint without a line in {@link #POLICY} fails the build, so no route can ship unclassified.
 * Status 404 means "the security layer let the request through" (this slice has no controllers for them).
 */
@WebMvcTest(controllers = CloudSecurityProbeController.class)
@Import(CloudSecurityConfig.class)
@ActiveProfiles("cloud")
class EndpointAuthorizationMatrixTest {
    enum Policy {
        /** ROLE_ADMIN only. */ ADMIN,
        /** Any authenticated human; the controller checks the operator assignment. */ AUTHENTICATED,
        /** Cognito machine client with scope handwash/producer. */ PRODUCER,
        /** Open to the network; the controller checks a station credential, session capability or one-time code. */ CONTROLLER_CREDENTIAL,
        /** Public, read-only, no data about people. */ PUBLIC,
        /** Never served in the cloud profile. */ DENIED
    }
    private record Rule(String method, String pattern, Policy policy) {}

    private static final List<Rule> POLICY = List.of(
        // Collisions first: these look like session capabilities ("/session/*") but are local-only helpers.
        new Rule("POST", "/api/v1/session/pair", Policy.DENIED),
        new Rule("POST", "/api/v1/auth/dashboard-login", Policy.DENIED),
        new Rule("*", "/api/v1/admin/**", Policy.ADMIN),
        new Rule("POST", "/api/v1/session", Policy.ADMIN),
        new Rule("GET", "/api/v1/session/active", Policy.ADMIN),
        new Rule("POST", "/api/v1/operator/key-login", Policy.CONTROLLER_CREDENTIAL),
        // A phone opens the console with a one-time QR code issued by a signed-in console.
        new Rule("POST", "/api/v1/operator/phone-login", Policy.CONTROLLER_CREDENTIAL),
        new Rule("*", "/api/v1/operator/**", Policy.AUTHENTICATED),
        // A phone camera paired by QR: a one-time code, then a camera-only token, authenticate inside the controller.
        new Rule("POST", "/api/v1/camera/redeem", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/camera/ticket", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/camera/state", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/auth/login", Policy.PRODUCER),
        new Rule("POST", "/api/v1/deteccion", Policy.PRODUCER),
        // A PC with YOLO pairs itself: it announces (capped per address) and polls with a secret device code; it gets
        // a key only after an ADMIN approves its short code.
        new Rule("POST", "/api/v1/producer/enroll", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/producer/enroll/poll", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/producer/session", Policy.PRODUCER),
        new Rule("POST", "/api/v1/session/*/producer-epoch", Policy.PRODUCER),
        new Rule("*", "/api/v1/session/*", Policy.CONTROLLER_CREDENTIAL),
        new Rule("GET", "/api/v1/session/*/attempts", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/session/*/websocket-ticket", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/session/*/webrtc-ticket", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/stations/enroll", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/stations/activate", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/stations/session", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/stations/resume", Policy.CONTROLLER_CREDENTIAL),
        new Rule("POST", "/api/v1/stations/evidence/*/*", Policy.CONTROLLER_CREDENTIAL),
        new Rule("GET", "/api/v1/stations/me", Policy.CONTROLLER_CREDENTIAL),
        new Rule("GET", "/api/v1/deployment/status", Policy.PUBLIC),
        new Rule("GET", "/api/v1/protocols", Policy.PUBLIC),
        new Rule("GET", "/api/v1/protocols/oms", Policy.PUBLIC),
        // Everything else, including the legacy unversioned /api/** routes and the local-only helpers.
        new Rule("*", "/**", Policy.DENIED));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Autowired MockMvc mvc;
    @MockitoBean(name = "cognitoJwtDecoder") JwtDecoder decoder;
    @MockitoBean(name = "cognitoIdJwtDecoder") JwtDecoder idTokenDecoder;

    private static Policy policyOf(Endpoint endpoint) {
        for (Rule rule : POLICY) {
            boolean method = rule.method().equals("*") || rule.method().equals(endpoint.method());
            if (method && MATCHER.match(rule.pattern(), endpoint.concretePath())) return rule.policy();
        }
        throw new AssertionError("Unclassified endpoint: " + endpoint);
    }

    private enum Caller { ANONYMOUS, NO_ROLE, OPERATOR, ADMIN, PRODUCER }

    private RequestPostProcessor as(Caller caller) {
        return switch (caller) {
            case ANONYMOUS -> request -> request;
            case NO_ROLE -> jwt();
            case OPERATOR -> jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
            case ADMIN -> jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
            case PRODUCER -> jwt().authorities(new SimpleGrantedAuthority("SCOPE_handwash/producer"));
        };
    }

    private static int expected(Policy policy, Caller caller) {
        final int passes = 404; // allowed by the security layer; no controller answers in this slice
        return switch (policy) {
            case ADMIN -> caller == Caller.ANONYMOUS ? 401 : caller == Caller.ADMIN ? passes : 403;
            case AUTHENTICATED -> caller == Caller.ANONYMOUS ? 401 : passes;
            case PRODUCER -> caller == Caller.ANONYMOUS ? 401 : caller == Caller.PRODUCER ? passes : 403;
            case CONTROLLER_CREDENTIAL, PUBLIC -> passes;
            case DENIED -> caller == Caller.ANONYMOUS ? 401 : 403;
        };
    }

    @Test
    void everyEndpointIsClassifiedAndTheClassificationIsNotStale() {
        List<Endpoint> endpoints = EndpointDiscovery.inCloudProfile();
        assertTrue(endpoints.size() > 60, "the scan found the real controllers: " + endpoints.size());
        endpoints.forEach(EndpointAuthorizationMatrixTest::policyOf);
        for (Rule rule : POLICY) {
            if (rule.pattern().equals("/**")) continue;
            assertTrue(endpoints.stream().anyMatch(e -> (rule.method().equals("*") || rule.method().equals(e.method()))
                && MATCHER.match(rule.pattern(), e.concretePath())), "rule matches no endpoint (stale): " + rule);
        }
    }

    @Test
    void controllersNamedAdminLiveOnlyUnderTheAdminPrefix() {
        for (Endpoint endpoint : EndpointDiscovery.inCloudProfile()) {
            if (endpoint.controller().startsWith("Admin")) {
                assertTrue(endpoint.path().startsWith("/api/v1/admin/"), "Admin controller outside the admin prefix: " + endpoint);
                assertEquals(Policy.ADMIN, policyOf(endpoint));
            }
            if (endpoint.controller().startsWith("Operator")) {
                assertTrue(endpoint.path().startsWith("/api/v1/operator/"), "Operator controller outside its prefix: " + endpoint);
            }
        }
    }

    @Test
    void everyCallerGetsExactlyTheStatusItsPolicyPromises() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<Policy, Integer> covered = new java.util.EnumMap<>(Policy.class);
        for (Endpoint endpoint : EndpointDiscovery.inCloudProfile()) {
            Policy policy = policyOf(endpoint);
            covered.merge(policy, 1, Integer::sum);
            for (Caller caller : Caller.values()) {
                int status = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(endpoint.method()), endpoint.concretePath())
                        .with(as(caller))).andReturn().getResponse().getStatus();
                if (status != expected(policy, caller)) {
                    violations.add(endpoint + " as " + caller + " -> " + status + " (policy " + policy + ", expected " + expected(policy, caller) + ")");
                }
            }
        }
        assertTrue(violations.isEmpty(), "Authorization violations:\n" + String.join("\n", violations));
        for (Policy policy : Policy.values()) assertTrue(covered.getOrDefault(policy, 0) > 0, "no endpoint exercises " + policy);
        assertFalse(covered.isEmpty());
    }
}
