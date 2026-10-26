package com.handwash.api.v1;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seam: what ADMIN sees and does with stations, end to end through the controller on H2. */
class AdminStationLifecycleTest {
    private EmbeddedDatabase database;
    private StationCredentialService stations;
    private ApiKeyService keys;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        var grants = new StationOperatorAccessRepository(jdbc);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, grants, audit);
        var operators = new StationOperatorAccessService(grants, stations, audit);
        mvc = MockMvcBuilders.standaloneSetup(new AdminStationController(stations, audit, operators, keys))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        database.shutdown();
    }

    private static RequestPostProcessor admin() {
        return request -> {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("admin-sub")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            var auth = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
            request.setUserPrincipal(auth);
            return request;
        };
    }

    @Test
    void revokingAStationAlsoRevokesItsKeys() throws Exception {
        String stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");

        mvc.perform(delete("/api/v1/admin/stations/" + stationId).with(admin())).andExpect(status().isNoContent());

        assertFalse(keys.verify(issued.key(), "203.0.113.5").accepted());
        assertFalse(keys.list().get(0).revokedAtEpochMs() == null);
    }

    @Test
    void theListSaysWhichStationsStillWaitForTheirMacToActivate() throws Exception {
        var pending = stations.provision("Pendiente", null, "admin-sub");
        var active = stations.provision("Activo", null, "admin-sub");
        stations.activate(active.activationToken());
        var legacy = stations.enroll(stations.issueEnrollmentCode("Viejo", "admin-sub").code());

        String json = mvc.perform(get("/api/v1/admin/stations").with(admin()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        java.util.List<java.util.Map<String, Object>> rows = com.jayway.jsonpath.JsonPath.read(json, "$");
        java.util.function.Function<String, Object> activatedOf = id -> rows.stream()
            .filter(row -> id.equals(row.get("stationId"))).findFirst().orElseThrow().get("activatedAtEpochMs");
        org.junit.jupiter.api.Assertions.assertNull(activatedOf.apply(pending.station().stationId()));
        org.junit.jupiter.api.Assertions.assertNotNull(activatedOf.apply(active.station().stationId()));
        org.junit.jupiter.api.Assertions.assertNotNull(activatedOf.apply(legacy.station().stationId()));
    }
}
