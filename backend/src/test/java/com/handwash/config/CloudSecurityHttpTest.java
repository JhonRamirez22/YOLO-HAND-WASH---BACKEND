package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CloudSecurityProbeController.class)
@Import(CloudSecurityConfig.class)
@ActiveProfiles("cloud")
class CloudSecurityHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean(name = "cognitoJwtDecoder") JwtDecoder decoder;
    @MockitoBean(name = "cognitoIdJwtDecoder") JwtDecoder idTokenDecoder;

    @Test
    void defaultDeniesUnknownRoutesAndDoesNotExposeAdminToOperators() throws Exception {
        mvc.perform(get("/api/v1/private-probe"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/security-probe")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/security-probe")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }

    @Test
    void separatesOperatorAndProducerRoutes() throws Exception {
        mvc.perform(post("/api/v1/session"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/session")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/session")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stations/session"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/deteccion")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/deteccion")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_handwash/producer"))))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/session/session-id/producer-epoch")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/session/session-id/producer-epoch")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_handwash/producer"))))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/camera/link")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void stationAdministrationRemainsAdminOnly() throws Exception {
        mvc.perform(get("/api/v1/admin/stations"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/stations")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/stations")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void operatorApiRequiresAnAuthenticatedHumanRequestAndAdminCannotUseItsRoleAsAssignment() throws Exception {
        mvc.perform(get("/api/v1/operator/security-probe"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/operator/security-probe")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/operators")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
    }
}
