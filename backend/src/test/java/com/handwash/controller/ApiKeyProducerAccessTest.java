package com.handwash.controller;

import com.handwash.api.v1.dto.ProducerBootstrapRequest;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.SyntheticTokens;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Seam: the producer bootstrap seen by a PC key. Being the station's bound key is what authorises the PC. */
class ApiKeyProducerAccessTest {
    private EmbeddedDatabase database;
    private ApiKeyService keys;
    private ProducerBootstrapController controller;
    private String stationA;
    private String stationB;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new StationCredentialService(new StationRepository(jdbc), audit);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, new StationOperatorAccessRepository(jdbc), audit);
        stationA = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        stationB = stations.provision("Lavamanos B", null, "admin-sub").station().stationId();
        controller = new ProducerBootstrapController(stations, mock(SessionManager.class), audit);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private ProducerBootstrapRequest request(String stationId) {
        return new ProducerBootstrapRequest(stationId, "model", "a".repeat(64), "dataset");
    }

    @Test
    void aPcKeyIsAuthorisedForItsOwnStationOnlyAndOnlyWhileTheKeyLives() {
        var pcA = keys.issue(stationA, "PC A", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub");
        var principal = SyntheticTokens.producer(pcA.keyId()).getToken();

        // 409 NO_ACTIVE_SESSION means authorisation passed and the station simply has no wash running.
        assertEquals(409, controller.bootstrap(request(stationA), principal).getStatusCode().value());
        assertEquals(403, controller.bootstrap(request(stationB), principal).getStatusCode().value());

        keys.revoke(pcA.keyId(), "admin-sub");
        assertEquals(403, controller.bootstrap(request(stationA), principal).getStatusCode().value());
    }
}
