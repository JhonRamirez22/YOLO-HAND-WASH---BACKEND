package com.handwash.security;

import com.handwash.config.CloudSecurityConfig;
import com.handwash.config.CloudSecurityProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seam: the HTTP filter chain with API keys in front of Cognito. ApiKeyService is mocked; its rules have their own tests. */
@WebMvcTest(controllers = CloudSecurityProbeController.class)
@Import({CloudSecurityConfig.class, ApiKeyProducerAuthenticator.class, KeySessionAuthenticator.class})
@ActiveProfiles("cloud")
class CredentialAuthenticationHttpTest {
    private static final String KEY = "hwk_0123456789abcdef_" + "A".repeat(43);
    private static final String CONSOLE_ONLY = "hwk_1111111111111111_" + "B".repeat(43);
    private static final String SESSION = "hws_" + "C".repeat(43);

    @Autowired MockMvc mvc;
    @MockitoBean ApiKeyService keys;
    @MockitoBean(name = "cognitoJwtDecoder") JwtDecoder decoder;
    @MockitoBean(name = "cognitoIdJwtDecoder") JwtDecoder idTokenDecoder;

    @Test
    void aProducerKeyOpensOnlyTheProducerRoutes() throws Exception {
        when(keys.verify(eq(KEY), any())).thenReturn(new ApiKeyService.Verification(true, false,
            "0123456789abcdef", "station-1", "PC mesa", EnumSet.of(ApiKeyScope.PRODUCER)));

        mvc.perform(post("/api/v1/deteccion").header("Authorization", "ApiKey " + KEY))
            .andExpect(status().isNotFound()); // passed security; this slice has no controller
        mvc.perform(get("/api/v1/admin/security-probe").header("Authorization", "ApiKey " + KEY))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operator/security-probe").header("Authorization", "ApiKey " + KEY))
            .andExpect(status().isOk()); // authenticated; the controller still demands an operator assignment
    }

    @Test
    void aConsoleOnlyKeyCannotActAsTheProducer() throws Exception {
        when(keys.verify(eq(CONSOLE_ONLY), any())).thenReturn(new ApiKeyService.Verification(true, false,
            "1111111111111111", "station-1", "María", EnumSet.of(ApiKeyScope.CONSOLE)));

        mvc.perform(post("/api/v1/deteccion").header("Authorization", "ApiKey " + CONSOLE_ONLY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAndLockedKeysAreRefusedBeforeAnyController() throws Exception {
        when(keys.verify(eq("hwk_bad"), any())).thenReturn(ApiKeyService.Verification.REJECTED);
        when(keys.verify(eq("hwk_locked"), any())).thenReturn(ApiKeyService.Verification.LOCKED);

        mvc.perform(post("/api/v1/deteccion").header("Authorization", "ApiKey hwk_bad"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/deteccion").header("Authorization", "ApiKey hwk_locked"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void aKeySessionIsAnOperatorAndNothingMore() throws Exception {
        when(keys.authenticateSession(SESSION)).thenReturn(new ApiKeyService.Verification(true, false,
            "0123456789abcdef", "station-1", "María", EnumSet.of(ApiKeyScope.CONSOLE)));

        mvc.perform(get("/api/v1/operator/security-probe").header("Authorization", "KeySession " + SESSION))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/security-probe").header("Authorization", "KeySession " + SESSION))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/deteccion").header("Authorization", "KeySession " + SESSION))
            .andExpect(status().isForbidden());
    }

    @Test
    void anExpiredOrRevokedSessionGetsUnauthorized() throws Exception {
        when(keys.authenticateSession(SESSION)).thenReturn(ApiKeyService.Verification.REJECTED);

        mvc.perform(get("/api/v1/operator/security-probe").header("Authorization", "KeySession " + SESSION))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void requestsWithoutTheseSchemesStillGoThroughCognito() throws Exception {
        mvc.perform(get("/api/v1/operator/security-probe")).andExpect(status().isUnauthorized());
    }
}
