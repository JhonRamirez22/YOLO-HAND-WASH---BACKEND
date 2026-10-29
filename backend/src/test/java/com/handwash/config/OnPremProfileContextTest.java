package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REAL application with profiles cloud + onprem: no Cognito, no S3, no KMS. Only the PostgreSQL URL (H2 here) and
 * the private-network guard are replaced; they have their own tests.
 */
@SpringBootTest(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.url=jdbc:h2:mem:onprem-context;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.url=jdbc:h2:mem:onprem-context;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=",
    "HANDWASH_CORS_ALLOWED_ORIGINS=https://frontend.example.org",
    "HANDWASH_MODEL_VERSION=model-test", "HANDWASH_MODEL_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "HANDWASH_DATASET_VERSION=dataset-test",
    "HANDWASH_AUDIT_CHAIN_SECRET=kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk",
    "HANDWASH_AUDIT_SEAL_DIR=/tmp",
    "HANDWASH_ADMIN_TOTP_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "handwash.admin.require-totp=true",
    "HANDWASH_BOOTSTRAP_ADMIN_USERNAME=jhon", "HANDWASH_BOOTSTRAP_ADMIN_PASSWORD=Correct-Horse-9",
    "handwash.session.token-ttl-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles({"cloud", "onprem"})
class OnPremProfileContextTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @TestConfiguration
    static class SkipInfrastructureGuard {
        @Bean(name = "cloudProfileSafetyGuard")
        static BeanFactoryPostProcessor noGuard() { return beanFactory -> { }; }
    }

    private String login(String totp) throws Exception {
        String body = "{\"username\":\"jhon\",\"password\":\"Correct-Horse-9\"" + (totp == null ? "" : ",\"totp\":\"" + totp + "\"") + "}";
        return mvc.perform(post("/api/v1/auth/admin/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theBootstrappedAdminEnrollsTotpLogsInAndReachesAdminRoutesThroughTheRealFilterChain() throws Exception {
        String first = login(null);
        String secret = com.jayway.jsonpath.JsonPath.read(first, "$.totpSecret");
        String code = com.handwash.security.admin.Totp.code(com.handwash.security.admin.Totp.fromBase32(secret),
            System.currentTimeMillis() / 1000 / 30);

        String session = login(code);
        String token = com.jayway.jsonpath.JsonPath.read(session, "$.token");

        mvc.perform(get("/api/v1/admin/stations").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/api-keys").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // A PC with YOLO pairs itself: public announce, ADMIN approves for a station, the PC receives its key once.
        var json = org.springframework.http.MediaType.APPLICATION_JSON;
        String announced = mvc.perform(post("/api/v1/producer/enroll").contentType(json).content("{\"deviceName\":\"DESKTOP-RTX\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String userCode = com.jayway.jsonpath.JsonPath.read(announced, "$.userCode");
        String deviceCode = com.jayway.jsonpath.JsonPath.read(announced, "$.deviceCode");
        mvc.perform(get("/api/v1/admin/producer-enrollments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/producer-enrollments").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String station = mvc.perform(post("/api/v1/admin/stations/provision").header("Authorization", "Bearer " + token)
            .contentType(json).content("{\"stationName\":\"Lavamanos\",\"operatorEmails\":[]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String stationId = com.jayway.jsonpath.JsonPath.read(station, "$.stationId");
        mvc.perform(post("/api/v1/admin/producer-enrollments/" + userCode + "/approve").contentType(json)
            .header("Authorization", "Bearer " + token).content("{\"stationId\":\"" + stationId + "\"}"))
            .andExpect(status().isNoContent());
        String delivered = mvc.perform(post("/api/v1/producer/enroll/poll").contentType(json).content("{\"deviceCode\":\"" + deviceCode + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("approved", com.jayway.jsonpath.JsonPath.read(delivered, "$.status"));
        assertTrue(((String) com.jayway.jsonpath.JsonPath.read(delivered, "$.key")).startsWith("hwk_"));

        mvc.perform(post("/api/v1/auth/admin/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/stations").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertEquals(401, mvc.perform(post("/api/v1/auth/admin/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"username\":\"jhon\",\"password\":\"wrong-password-1\"}")).andReturn().getResponse().getStatus());
    }

    @Test
    void startsWithoutCognitoAndRejectsEveryExternalJwt() throws Exception {
        assertNotNull(context.getBean("adminAuditChainController"));
        JwtDecoder decoder = context.getBean("cognitoJwtDecoder", JwtDecoder.class);
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> decoder.decode("a.b.c"));
        mvc.perform(get("/api/v1/admin/stations").header("Authorization", "Bearer aaaa.bbbb.cccc"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
