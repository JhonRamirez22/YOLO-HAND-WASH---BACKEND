package com.handwash.security.admin;

import com.handwash.config.CloudSecurityConfig;
import com.handwash.config.CloudSecurityProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seam: login endpoint + the filter chain with ADMIN sessions. AdminAuthService is mocked; its rules have their own tests. */
@WebMvcTest(controllers = {CloudSecurityProbeController.class, AdminAuthController.class})
@Import({CloudSecurityConfig.class, AdminSessionAuthenticator.class})
@ActiveProfiles({"cloud", "onprem"})
class AdminAuthHttpTest {
    private static final String TOKEN = "hwa_" + "D".repeat(43);

    @Autowired MockMvc mvc;
    @MockitoBean AdminAuthService auth;
    @MockitoBean(name = "cognitoJwtDecoder") JwtDecoder decoder;
    @MockitoBean(name = "cognitoIdJwtDecoder") JwtDecoder idTokenDecoder;

    private static String body(String user, String pass, String totp) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"" + (totp == null ? "" : ",\"totp\":\"" + totp + "\"") + "}";
    }

    @Test
    void successReturnsTheSessionTokenAndNothingElse() throws Exception {
        when(auth.login(eq("jhon"), eq("Correct-Horse-9"), eq("123456"), any())).thenReturn(
            new AdminAuthService.LoginOutcome(AdminAuthService.Status.SUCCESS, TOKEN, 99L, "jhon", null, null, 0));

        mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON).content(body("jhon", "Correct-Horse-9", "123456")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value(TOKEN))
            .andExpect(jsonPath("$.username").value("jhon"))
            .andExpect(jsonPath("$.totpSecret").doesNotExist());
    }

    @Test
    void firstLoginAsksForEnrollmentAndWrongCredentialsAreAGenericUnauthorizedAndLocksAre423() throws Exception {
        when(auth.login(any(), any(), any(), any())).thenReturn(AdminAuthService.LoginOutcome.of(AdminAuthService.Status.INVALID));
        when(auth.login(eq("jhon"), eq("pw"), eq(null), any())).thenReturn(new AdminAuthService.LoginOutcome(
            AdminAuthService.Status.ENROLLMENT_REQUIRED, null, 0, "jhon", "SECRETBASE32", "otpauth://totp/x", 0));
        when(auth.login(eq("bad"), any(), any(), any())).thenReturn(AdminAuthService.LoginOutcome.of(AdminAuthService.Status.INVALID));
        when(auth.login(eq("locked"), any(), any(), any())).thenReturn(new AdminAuthService.LoginOutcome(
            AdminAuthService.Status.LOCKED, null, 0, null, null, null, 600));

        mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON).content(body("jhon", "pw", null)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enrollmentRequired").value(true))
            .andExpect(jsonPath("$.totpSecret").value("SECRETBASE32")).andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON).content(body("bad", "x", null)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("CREDENCIALES_INVALIDAS"));
        mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON).content(body("locked", "x", null)))
            .andExpect(status().isLocked()).andExpect(jsonPath("$.retryAfterSeconds").value(600));
        mvc.perform(post("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminSessionTokenIsAnAdminAndNothingElse() throws Exception {
        when(auth.authenticate(TOKEN)).thenReturn(Optional.of(new AdminAuthService.AdminPrincipal(7, "jhon")));

        mvc.perform(get("/api/v1/admin/security-probe").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/deteccion").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden());
    }

    @Test
    void expiredOrUnknownSessionsAreUnauthorizedAndOtherBearersStillGoToTheJwtDecoder() throws Exception {
        when(auth.authenticate(TOKEN)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/admin/security-probe").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/security-probe")).andExpect(status().isUnauthorized());
    }
}
