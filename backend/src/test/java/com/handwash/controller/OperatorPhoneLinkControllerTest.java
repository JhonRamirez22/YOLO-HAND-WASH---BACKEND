package com.handwash.controller;

import com.handwash.api.v1.dto.KeyLoginResponse;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyScope;
import com.handwash.security.ApiKeyService;
import com.handwash.security.PhoneLinkRegistry;
import com.handwash.security.SyntheticTokens;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
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

/** The operator of a signed-in console opens a phone with a one-time code; the phone gets its own session, never the key. */
class OperatorPhoneLinkControllerTest {
    private EmbeddedDatabase database;
    private ApiKeyService keys;
    private OperatorPhoneLinkController controller;
    private String stationId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new StationCredentialService(new StationRepository(jdbc), audit);
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations, new StationOperatorAccessRepository(jdbc), audit);
        stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        controller = new OperatorPhoneLinkController(keys, stations, new PhoneLinkRegistry());
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    @SuppressWarnings("unchecked")
    void aConsoleKeyIssuesACodeThePhoneTradesForItsOwnSessionExactlyOnce() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();

        var link = (Map<String, Object>) controller.issueLink(principal).getBody();
        String code = (String) link.get("code");
        assertEquals(120, link.get("expiresInSeconds"));

        var response = controller.redeem(new OperatorPhoneLinkController.RedeemRequest(code));
        assertEquals(200, response.getStatusCode().value());
        var login = (KeyLoginResponse) response.getBody();
        assertTrue(login.sessionToken().startsWith("hws_"));
        assertEquals(stationId, login.stationId());
        assertNull(login.stationCredential());
        assertTrue(keys.authenticateSession(login.sessionToken()).accepted());

        assertEquals(401, controller.redeem(new OperatorPhoneLinkController.RedeemRequest(code)).getStatusCode().value());
    }

    @Test
    void onlyAKeyConsolePrincipalMayIssueACode() {
        var producer = SyntheticTokens.producer(keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub").keyId()).getToken();
        assertEquals(403, controller.issueLink(producer).getStatusCode().value());
        assertEquals(403, controller.issueLink(null).getStatusCode().value());
    }

    @Test
    void aRevokedKeyCannotOpenAPhoneEvenWithAFreshCode() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var principal = SyntheticTokens.console(issued.keyId()).getToken();
        @SuppressWarnings("unchecked") String code = (String) ((Map<String, Object>) controller.issueLink(principal).getBody()).get("code");

        keys.revoke(issued.keyId(), "admin-sub");

        assertEquals(401, controller.redeem(new OperatorPhoneLinkController.RedeemRequest(code)).getStatusCode().value());
    }

    @Test
    void malformedRequestsAreRefused() {
        assertEquals(400, controller.redeem(null).getStatusCode().value());
        assertEquals(400, controller.redeem(new OperatorPhoneLinkController.RedeemRequest("  ")).getStatusCode().value());
    }
}
