package com.handwash.controller;

import com.handwash.api.v1.AdminApiKeyController;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyService;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seam: the admin key endpoints and the console key-login endpoint, with the real ApiKeyService on H2. */
class ApiKeyControllersTest {
    private EmbeddedDatabase database;
    private StationCredentialService stations;
    private ApiKeyService keys;
    private MockMvc mvc;
    private String stationId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, new StationOperatorAccessRepository(jdbc), audit);
        stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        mvc = MockMvcBuilders.standaloneSetup(new AdminApiKeyController(keys, stations),
            new OperatorKeyLoginController(keys, stations))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        database.shutdown();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return request -> {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("admin-sub")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            request.setUserPrincipal(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                (org.springframework.security.core.Authentication) request.getUserPrincipal());
            return request;
        };
    }

    @Test
    void adminCreatesAKeyShownOnceThenListsItWithoutTheSecretAndRevokesIt() throws Exception {
        String body = "{\"stationId\":\"" + stationId + "\",\"label\":\"María – mañana\",\"scopes\":[\"CONSOLE\",\"PRODUCER\"]}";
        String created = mvc.perform(post("/api/v1/admin/api-keys").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key", startsWith("hwk_")))
            .andReturn().getResponse().getContentAsString();
        String key = com.jayway.jsonpath.JsonPath.read(created, "$.key");
        String keyId = com.jayway.jsonpath.JsonPath.read(created, "$.keyId");

        mvc.perform(get("/api/v1/admin/api-keys").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].keyId").value(keyId))
            .andExpect(jsonPath("$[0].label").value("María – mañana"))
            .andExpect(content().string(not(containsString(key.substring(21)))));

        mvc.perform(delete("/api/v1/admin/api-keys/" + keyId).with(admin())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/admin/api-keys/" + keyId).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsGetClearClientErrors() throws Exception {
        mvc.perform(post("/api/v1/admin/api-keys").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationId\":\"" + stationId + "\",\"label\":\"\",\"scopes\":[\"CONSOLE\"]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/api-keys").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationId\":\"" + stationId + "\",\"label\":\"x\",\"scopes\":[\"ROOT\"]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/api-keys").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationId\":\"not-a-station\",\"label\":\"x\",\"scopes\":[\"CONSOLE\"]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void keyLoginTradesAConsoleKeyForASessionAndCanLinkTheMac() throws Exception {
        var issued = keys.issue(stationId, "María", java.util.EnumSet.of(com.handwash.security.ApiKeyScope.CONSOLE), "admin-sub");

        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + issued.key() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionToken", startsWith("hws_")))
            .andExpect(jsonPath("$.stationId").value(stationId))
            .andExpect(jsonPath("$.stationName").value("Lavamanos A"))
            .andExpect(jsonPath("$.stationCredential").doesNotExist());

        String linked = mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + issued.key() + "\",\"linkStation\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String credential = com.jayway.jsonpath.JsonPath.read(linked, "$.stationCredential");
        assertNotNull(stations.authenticate(credential), "the Mac gets a working station credential");
    }

    @Test
    void keyLoginRefusesBadKeysProducerOnlyKeysAndLockedAddresses() throws Exception {
        var pc = keys.issue(stationId, "PC", java.util.EnumSet.of(com.handwash.security.ApiKeyScope.PRODUCER), "admin-sub");
        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + pc.key() + "\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("KEY_NO_VALIDA"));
        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"hwk_" + "0".repeat(16) + "_" + "A".repeat(43) + "\"}"));
        }
        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"hwk_" + "0".repeat(16) + "_" + "A".repeat(43) + "\"}"))
            .andExpect(status().isTooManyRequests());
        // The same address still lets a real console key in: throttling slows guessing, it never locks people out.
        var console = keys.issue(stationId, "María", java.util.EnumSet.of(com.handwash.security.ApiKeyScope.CONSOLE), "admin-sub");
        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + console.key() + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void aSecondDeviceDoesNotKickTheMacUnlessItForcesTheLink() throws Exception {
        var issued = keys.issue(stationId, "María", java.util.EnumSet.of(com.handwash.security.ApiKeyScope.CONSOLE), "admin-sub");
        String link = "{\"key\":\"" + issued.key() + "\",\"linkStation\":true}";
        String first = mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON).content(link))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String macCredential = com.jayway.jsonpath.JsonPath.read(first, "$.stationCredential");

        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON).content(link))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.macAlreadyLinked").value(true))
            .andExpect(jsonPath("$.stationCredential").doesNotExist())
            .andExpect(jsonPath("$.sessionToken", startsWith("hws_")));
        assertNotNull(stations.authenticate(macCredential), "the first Mac still works");

        mvc.perform(post("/api/v1/operator/key-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + issued.key() + "\",\"linkStation\":true,\"forceLink\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stationCredential").exists());
    }
}
