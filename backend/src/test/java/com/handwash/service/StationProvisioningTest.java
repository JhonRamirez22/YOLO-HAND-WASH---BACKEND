package com.handwash.service;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationProvisioningTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private StationCredentialService stations;
    private StationOperatorAccessService operators;
    private final List<String> disabled = new ArrayList<>();
    private int created;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        stations = new StationCredentialService(new StationRepository(jdbc), audit);
        OperatorAccountDirectory directory = new OperatorAccountDirectory() {
            @Override public CreatedAccount create(String email) {
                if (email.startsWith("dup")) throw new AlreadyExistsException("ya existe");
                return new CreatedAccount("sub-" + (++created), "Temp-" + created + "!aaaa");
            }
            @Override public void disable(String subject) { disabled.add(subject); }
            @Override public boolean available() { return true; }
        };
        operators = new StationOperatorAccessService(new StationOperatorAccessRepository(jdbc), stations,
            audit, directory);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void provisionedStationIsUnusableUntilTheMacRedeemsTheTokenOnce() {
        var provisioned = stations.provision(" Lavamanos A ", "producer-client_1", "admin-subject");
        String stationId = provisioned.station().stationId();

        assertEquals("Lavamanos A", provisioned.station().stationName());
        assertEquals("producer-client_1", stations.activeStation(stationId).producerClientId());
        assertNotEquals(provisioned.activationToken(), jdbc.queryForObject(
            "SELECT activation_hash FROM handwash_stations WHERE station_id = ?", String.class, stationId));
        assertNull(stations.authenticate(provisioned.activationToken()), "the token is not a device credential");

        var activated = stations.activate(provisioned.activationToken());

        assertNotNull(activated);
        assertEquals(stationId, activated.station().stationId());
        assertEquals(stationId, stations.authenticate(activated.credential()).stationId());
        assertNull(stations.activate(provisioned.activationToken()), "single use");
        assertNull(jdbc.queryForObject(
            "SELECT activation_hash FROM handwash_stations WHERE station_id = ?", String.class, stationId));
    }

    @Test
    void expiredOrRevokedActivationIsRefusedAndAdminCanReissueUntilActivated() {
        var provisioned = stations.provision("Lavamanos A", null, "admin-subject");
        String stationId = provisioned.station().stationId();
        jdbc.update("UPDATE handwash_stations SET activation_expires_at_epoch_ms = 1 WHERE station_id = ?", stationId);
        assertNull(stations.activate(provisioned.activationToken()), "expired");

        var reissued = stations.reissueActivation(stationId, "admin-subject");
        assertNotEquals(provisioned.activationToken(), reissued.code());
        assertNull(stations.activate(provisioned.activationToken()), "old token replaced");
        assertNotNull(stations.activate(reissued.code()));
        assertThrows(IllegalArgumentException.class, () -> stations.reissueActivation(stationId, "admin-subject"),
            "already activated");

        var revoked = stations.provision("Lavamanos B", null, "admin-subject");
        stations.revoke(revoked.station().stationId(), "admin-subject");
        assertNull(stations.activate(revoked.activationToken()));
    }

    @Test
    void rejectsShortOrMalformedActivationTokens() {
        assertNull(stations.activate(null));
        assertNull(stations.activate("short"));
        assertNull(stations.activate("x".repeat(40) + " ; DROP TABLE handwash_stations"));
        assertThrows(IllegalArgumentException.class, () -> stations.provision("A", "bad client!", "admin-subject"));
    }

    @Test
    void operatorAccountIsGrantedToItsStationAndDisabledOnRevoke() {
        String stationId = stations.provision("Lavamanos A", null, "admin-subject").station().stationId();

        var first = operators.createAccount(stationId, " Nurse@Hospital.org ", "admin-subject");
        var second = operators.createAccount(stationId, "other@hospital.org", "admin-subject");

        assertEquals("nurse@hospital.org", first.email());
        assertNotNull(first.temporaryPassword());
        assertEquals(stationId, operators.activeGrant(first.subject()).stationId());
        assertEquals(stationId, operators.activeGrant(second.subject()).stationId());
        assertFalse(jdbc.queryForObject("SELECT COUNT(*) FROM station_operator_grants", Integer.class) != 2);

        assertEquals(stationId, operators.revokeGrant(first.subject(), "admin-subject"));
        assertNull(operators.activeGrant(first.subject()), "access is cut by the grant at once");
        assertEquals(List.of(first.subject()), disabled);
        assertNotNull(operators.activeGrant(second.subject()));
    }

    @Test
    void duplicateAccountAndUnavailableDirectoryDoNotCreateGrants() {
        String stationId = stations.provision("Lavamanos A", null, "admin-subject").station().stationId();
        assertThrows(OperatorAccountDirectory.AlreadyExistsException.class,
            () -> operators.createAccount(stationId, "dup@hospital.org", "admin-subject"));
        assertThrows(IllegalArgumentException.class,
            () -> operators.createAccount(stationId, "not-an-email", "admin-subject"));
        assertThrows(IllegalArgumentException.class,
            () -> operators.createAccount("not-a-uuid", "a@hospital.org", "admin-subject"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM station_operator_grants", Integer.class));

        var legacy = new StationOperatorAccessService(new StationOperatorAccessRepository(jdbc), stations,
            new SessionAuditService(new SessionAuditRepository(jdbc)));
        assertFalse(legacy.accountsAvailable());
        assertThrows(OperatorAccountDirectory.UnavailableException.class,
            () -> legacy.createAccount(stationId, "a@hospital.org", "admin-subject"));
        assertTrue(disabled.isEmpty());
    }

    @Test
    void anotherDeviceCannotKickTheStationsMacUnlessItAsksToReplaceIt() {
        String stationId = stations.provision("Lavamanos A", null, "admin-subject").station().stationId();

        var first = stations.linkStationByKey(stationId, "apikey_a", false);
        assertFalse(first.alreadyLinked());
        assertNotNull(stations.authenticate(first.credential()));

        var refused = stations.linkStationByKey(stationId, "apikey_b", false);
        assertTrue(refused.alreadyLinked());
        assertNull(refused.credential());
        assertNotNull(stations.authenticate(first.credential()), "the Mac keeps working");

        var replaced = stations.linkStationByKey(stationId, "apikey_b", true);
        assertNotNull(stations.authenticate(replaced.credential()));
        assertNull(stations.authenticate(first.credential()), "replacing is an explicit decision");
    }

    @Test
    void stationsActivatedByLinkOrEnrolledTheOldWayCountAsLinked() {
        var provisioned = stations.provision("Lavamanos A", null, "admin-subject");
        assertNotNull(stations.activate(provisioned.activationToken()));
        assertTrue(stations.linkStationByKey(provisioned.station().stationId(), "apikey_a", false).alreadyLinked());

        var legacy = stations.enroll(stations.issueEnrollmentCode("Viejo", "admin-subject").code());
        assertTrue(stations.linkStationByKey(legacy.station().stationId(), "apikey_a", false).alreadyLinked());
        assertNull(stations.linkStationByKey("not-a-uuid", "apikey_a", false));
    }
}
