package com.handwash.controller;

import com.handwash.camera.CameraAgentRegistry;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.SyntheticTokens;
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

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The console of a station asks for the iPhone camera helper on that station's Mac, and the helper reports back. */
class OperatorCameraAgentControllerTest {
    private EmbeddedDatabase database;
    private ApiKeyService keys;
    private OperatorCameraAgentController controller;
    private String stationA;
    private String stationB;
    private com.handwash.service.StationCredentialService stations;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        var grants = new StationOperatorAccessRepository(jdbc);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, grants, audit);
        var access = new StationOperatorAccessService(grants, stations, audit);
        stationA = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        stationB = stations.provision("Lavamanos B", null, "admin-sub").station().stationId();
        controller = new OperatorCameraAgentController(access, new CameraAgentRegistry());
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private org.springframework.security.oauth2.jwt.Jwt console(String stationId) {
        return SyntheticTokens.console(keys.issue(stationId, "op", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub").keyId()).getToken();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConsoleRequestsTheCameraTheHelperSeesItAndReportsBack() {
        var operatorA = console(stationA);

        assertEquals(204, controller.request(operatorA, new OperatorCameraAgentController.RequestBody(true)).getStatusCode().value());
        var beat = (Map<String, Object>) controller.heartbeat(operatorA,
            new OperatorCameraAgentController.HeartbeatBody("Cámara de iPhone De Jhon", "streaming", "enviando")).getBody();
        assertEquals(true, beat.get("wanted"));

        var view = (CameraAgentRegistry.View) controller.view(operatorA).getBody();
        assertTrue(view.online());
        assertTrue(view.wanted());
        assertEquals("streaming", view.state());
    }

    @Test
    void aConsoleOnlyReachesItsOwnStationsHelper() {
        var operatorA = console(stationA);
        var operatorB = console(stationB);
        controller.heartbeat(operatorA, new OperatorCameraAgentController.HeartbeatBody("iPhone", "ready", "ok"));

        assertTrue(((CameraAgentRegistry.View) controller.view(operatorA).getBody()).online());
        assertFalse(((CameraAgentRegistry.View) controller.view(operatorB).getBody()).online());
    }

    @Test
    void withoutAGrantOrWithABadBodyNothingHappens() {
        var producer = SyntheticTokens.producer(keys.issue(stationA, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub").keyId()).getToken();
        assertEquals(403, controller.view(producer).getStatusCode().value());
        assertEquals(403, controller.view(null).getStatusCode().value());
        assertEquals(403, controller.request(producer, new OperatorCameraAgentController.RequestBody(true)).getStatusCode().value());
        var operatorA = console(stationA);
        assertEquals(400, controller.request(operatorA, null).getStatusCode().value());
        assertEquals(400, controller.heartbeat(operatorA, null).getStatusCode().value());
    }

    @Test
    void aRevokedKeyLosesAccessToTheHelper() {
        var issued = keys.issue(stationA, "op", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();
        assertEquals(200, controller.view(principal).getStatusCode().value());
        keys.revoke(issued.keyId(), "admin-sub");
        assertEquals(403, controller.view(principal).getStatusCode().value());
    }
}
