package com.handwash.api.v1;

import com.handwash.controller.OperatorPhoneLinkController;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.PhoneLinkRegistry;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An ADMIN hands one link to a wash station and it works: no key to type, no activation code. */
class AdminStationLinkControllerTest {
    private EmbeddedDatabase database;
    private ApiKeyService keys;
    private StationCredentialService stations;
    private AdminStationLinkController admin;
    private OperatorPhoneLinkController phone;
    private String stationId;
    private final Jwt adminJwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("sub", "admin:1"));

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, new StationOperatorAccessRepository(jdbc), audit);
        var links = new PhoneLinkRegistry();
        admin = new AdminStationLinkController(keys, stations, links);
        phone = new OperatorPhoneLinkController(keys, stations, links);
        stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @SuppressWarnings("unchecked")
    private String issue(boolean mac, Integer minutes) {
        var response = admin.issue(adminJwt, stationId, new AdminStationLinkController.LinkRequest(minutes, mac));
        assertEquals(200, response.getStatusCode().value());
        return (String) ((Map<String, Object>) response.getBody()).get("code");
    }

    @Test
    void theLinkOpensTheConsoleOfThatStationWithoutAnyKeyAndWorksOnce() {
        String code = issue(false, 60);

        var response = phone.redeem(new OperatorPhoneLinkController.RedeemRequest(code));

        assertEquals(200, response.getStatusCode().value());
        var login = (com.handwash.api.v1.dto.KeyLoginResponse) response.getBody();
        assertEquals(stationId, login.stationId());
        assertNull(login.stationCredential(), "a phone link does not take over the station screen");
        assertTrue(keys.authenticateSession(login.sessionToken()).accepted());
        assertEquals(401, phone.redeem(new OperatorPhoneLinkController.RedeemRequest(code)).getStatusCode().value());
    }

    @Test
    void theLinkForTheMacAlsoMakesItTheStationScreen() {
        var response = phone.redeem(new OperatorPhoneLinkController.RedeemRequest(issue(true, 60)));
        var login = (com.handwash.api.v1.dto.KeyLoginResponse) response.getBody();
        assertNotNull(login.stationCredential());
        assertTrue(login.stationCredential().length() >= 40);
    }

    @Test
    void severalLinksForTheSameStationReuseOneKeyInsteadOfExhaustingTheLimit() {
        for (int i = 0; i < 12; i++) assertNotNull(issue(i % 2 == 0, 30));
        long linkKeys = keys.list().stream().filter(k -> k.stationId().equals(stationId) && "Enlace del puesto".equals(k.label())).count();
        assertEquals(1, linkKeys);
        assertTrue(keys.list().stream().filter(k -> k.stationId().equals(stationId)).allMatch(k -> k.scopes().equals(java.util.EnumSet.of(ApiKeyScope.CONSOLE))));
    }

    @Test
    void aRevokedLinkKeyIsReplacedAndTheOldLinksStopWorking() {
        String first = issue(false, 60);
        String keyId = keys.list().stream().filter(k -> "Enlace del puesto".equals(k.label())).findFirst().orElseThrow().keyId();
        keys.revoke(keyId, "admin-sub");

        assertEquals(401, phone.redeem(new OperatorPhoneLinkController.RedeemRequest(first)).getStatusCode().value());
        assertEquals(200, phone.redeem(new OperatorPhoneLinkController.RedeemRequest(issue(false, 60))).getStatusCode().value());
    }

    @Test
    void onlyAdminsAndOnlyForActiveStationsWithSaneInputs() {
        assertEquals(401, admin.issue(null, stationId, new AdminStationLinkController.LinkRequest(60, false)).getStatusCode().value());
        assertEquals(404, admin.issue(adminJwt, "11111111-1111-4111-8111-111111111111", new AdminStationLinkController.LinkRequest(60, false)).getStatusCode().value());
        assertEquals(400, admin.issue(adminJwt, "not-a-uuid", new AdminStationLinkController.LinkRequest(60, false)).getStatusCode().value());
        assertEquals(200, admin.issue(adminJwt, stationId, null).getStatusCode().value(), "defaults apply when nothing is sent");
        stations.revoke(stationId, "admin-sub");
        assertEquals(404, admin.issue(adminJwt, stationId, new AdminStationLinkController.LinkRequest(60, false)).getStatusCode().value());
    }
}
