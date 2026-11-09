package com.handwash.security;

import com.handwash.repository.SessionAuditRepository;
import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.repository.StationRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seam: ApiKeyService. Behaviour only; storage is observed through the service, never by recomputing its hash. */
class ApiKeyServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ApiKeyService keys;
    private StationCredentialService stationService;
    private StationOperatorAccessRepository grantRepo;
    private String stationId;
    private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new StationCredentialService(new StationRepository(jdbc), audit);
        stationService = stations;
        grantRepo = new StationOperatorAccessRepository(jdbc);
        stationId = stations.provision("Lavamanos A", null, "admin-sub").station().stationId();
        keys = new ApiKeyService(new ApiKeyRepository(jdbc), stations,
            new StationOperatorAccessRepository(jdbc), audit, clock::get);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void anIssuedKeyIsShownOnceAndVerifiesToItsStationLabelAndScopes() {
        var issued = keys.issue(stationId, "María – turno mañana",
            EnumSet.of(ApiKeyScope.CONSOLE, ApiKeyScope.PRODUCER), "admin-sub");

        assertTrue(issued.key().startsWith("hwk_"));
        var verified = keys.verify(issued.key(), "203.0.113.5");

        assertTrue(verified.accepted());
        assertEquals(stationId, verified.stationId());
        assertEquals("María – turno mañana", verified.label());
        assertEquals(EnumSet.of(ApiKeyScope.CONSOLE, ApiKeyScope.PRODUCER), verified.scopes());
        assertEquals(issued.keyId(), verified.keyId());
        assertNotEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_keys", Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM api_keys WHERE secret_hash LIKE ?", Integer.class, "%" + issued.key().substring(21) + "%"),
            "the secret itself is never stored");
    }

    @Test
    void wrongMalformedOrUnknownKeysAreRejectedWithoutSayingWhy() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        String forged = issued.key().substring(0, 21) + "A".repeat(43);

        assertFalse(keys.verify(forged, "203.0.113.5").accepted());
        assertFalse(keys.verify("hwk_" + "0".repeat(16) + "_" + "A".repeat(43), "203.0.113.5").accepted());
        for (String garbage : new String[] {"", "hwk_", "not a key", "hwk_short", null,
            issued.key() + "x", issued.key().substring(0, 40)}) {
            assertFalse(keys.verify(garbage, "203.0.113.5").accepted(), String.valueOf(garbage));
        }
        assertTrue(keys.verify(issued.key(), "203.0.113.5").accepted(), "the real key still works");
    }

    @Test
    void aRevokedKeyStopsWorkingAtOnceAndRevokingTwiceIsHarmless() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");

        assertTrue(keys.revoke(issued.keyId(), "admin-sub"));
        assertFalse(keys.verify(issued.key(), "203.0.113.5").accepted());
        assertFalse(keys.revoke(issued.keyId(), "admin-sub"));
        assertFalse(keys.revoke("0000000000000000", "admin-sub"));
    }

    @Test
    void tenFailuresThrottleFurtherGuessesButNeverRejectAValidKey() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        String forged = issued.key().substring(0, 21) + "B".repeat(43);

        for (int attempt = 0; attempt < 10; attempt++) keys.verify(forged, "198.51.100.9");

        var guess = keys.verify(forged, "198.51.100.9");
        assertFalse(guess.accepted());
        assertTrue(guess.locked(), "more guesses from that address are throttled");
        // Behind the load balancer many people share an address: a stranger's guesses must not lock out the real key.
        assertTrue(keys.verify(issued.key(), "198.51.100.9").accepted());
        assertTrue(keys.login(issued.key(), "198.51.100.9").accepted());

        clock.addAndGet(16 * 60_000L);
        assertFalse(keys.verify(forged, "198.51.100.9").locked(), "the throttle expires");
    }

    @Test
    void listShowsLastUseAndNeverTheSecret() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        assertEquals(null, keys.list().get(0).lastUsedAtEpochMs());

        keys.verify(issued.key(), "203.0.113.5");
        clock.addAndGet(65_000); // last use is written at most once a minute
        keys.verify(issued.key(), "203.0.113.6");

        var listed = keys.list().get(0);
        assertEquals(issued.keyId(), listed.keyId());
        assertEquals(1_065_000L, listed.lastUsedAtEpochMs());
        assertEquals("203.0.113.6", listed.lastUsedIp());
        assertFalse(listed.toString().contains(issued.key().substring(21)));
    }

    @Test
    void aConsoleKeyGrantsItsStationToTheKeyAndRevokingTakesItAway() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        String subject = ApiKeyService.subjectOf(issued.keyId());

        assertEquals(stationId, grantRepo.findActiveGrant(subject).stationId());
        assertEquals("María", grantRepo.findActiveGrant(subject).email());

        keys.revoke(issued.keyId(), "admin-sub");
        assertEquals(null, grantRepo.findActiveGrant(subject));
    }

    @Test
    void aProducerKeyBindsTheStationPcAndOnlyItsOwnBindingIsClearedOnRevoke() {
        var first = keys.issue(stationId, "PC mesa", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub");
        assertEquals(ApiKeyService.producerClientIdOf(first.keyId()), stationService.activeStation(stationId).producerClientId());
        assertEquals(null, grantRepo.findActiveGrant(ApiKeyService.subjectOf(first.keyId())), "no console access");

        var second = keys.issue(stationId, "PC nueva", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub");
        keys.revoke(first.keyId(), "admin-sub");
        assertEquals(ApiKeyService.producerClientIdOf(second.keyId()), stationService.activeStation(stationId).producerClientId());

        keys.revoke(second.keyId(), "admin-sub");
        assertEquals(null, stationService.activeStation(stationId).producerClientId());
    }

    @Test
    void refusesKeysForUnknownStationsBlankLabelsNoScopesAndTooManyConsoleKeys() {
        var console = EnumSet.of(ApiKeyScope.CONSOLE);
        assertThrows(IllegalArgumentException.class, () -> keys.issue("not-a-uuid", "María", console, "admin-sub"));
        assertThrows(IllegalArgumentException.class, () -> keys.issue(java.util.UUID.randomUUID().toString(), "María", console, "admin-sub"));
        assertThrows(IllegalArgumentException.class, () -> keys.issue(stationId, "  ", console, "admin-sub"));
        assertThrows(IllegalArgumentException.class, () -> keys.issue(stationId, "x".repeat(81), console, "admin-sub"));
        assertThrows(IllegalArgumentException.class, () -> keys.issue(stationId, "María", EnumSet.noneOf(ApiKeyScope.class), "admin-sub"));
        for (int i = 0; i < 5; i++) keys.issue(stationId, "Operador " + i, console, "admin-sub");
        assertThrows(IllegalStateException.class, () -> keys.issue(stationId, "Sexto", console, "admin-sub"));
    }

    @Test
    void consoleLoginTradesTheKeyForASessionThatDiesWithTheKeyOrAfterTwelveHours() {
        var issued = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");

        var login = keys.login(issued.key(), "203.0.113.5");
        assertTrue(login.accepted());
        assertTrue(login.sessionToken().startsWith("hws_"));
        assertEquals(stationId, login.verification().stationId());

        var viaSession = keys.authenticateSession(login.sessionToken());
        assertTrue(viaSession.accepted());
        assertEquals("María", viaSession.label());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_key_sessions WHERE token_hash LIKE ?",
            Integer.class, "%" + login.sessionToken().substring(4) + "%"), "the token itself is not stored");

        clock.addAndGet(12 * 3_600_000L + 1);
        assertFalse(keys.authenticateSession(login.sessionToken()).accepted(), "expired");
    }

    @Test
    void revokingTheKeyEndsItsSessionsAtOnceAndAProducerOnlyKeyCannotOpenTheConsole() {
        var console = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var login = keys.login(console.key(), "203.0.113.5");
        keys.revoke(console.keyId(), "admin-sub");
        assertFalse(keys.authenticateSession(login.sessionToken()).accepted());
        assertFalse(keys.login(console.key(), "203.0.113.5").accepted());

        var pc = keys.issue(stationId, "PC mesa", EnumSet.of(ApiKeyScope.PRODUCER), "admin-sub");
        assertFalse(keys.login(pc.key(), "203.0.113.5").accepted());
        assertFalse(keys.authenticateSession("hws_" + "A".repeat(43)).accepted());
        assertFalse(keys.authenticateSession(null).accepted());
    }

    @Test
    void revokingAStationEndsEveryKeySessionAndPcBindingOfThatStationOnly() {
        String other = stationService.provision("Otro", null, "admin-sub").station().stationId();
        var mine = keys.issue(stationId, "María", EnumSet.of(ApiKeyScope.CONSOLE, ApiKeyScope.PRODUCER), "admin-sub");
        var theirs = keys.issue(other, "Pedro", EnumSet.of(ApiKeyScope.CONSOLE), "admin-sub");
        var session = keys.login(mine.key(), "203.0.113.5");

        assertEquals(1, keys.revokeAllForStation(stationId, "admin-sub"));

        assertFalse(keys.verify(mine.key(), "203.0.113.5").accepted());
        assertFalse(keys.authenticateSession(session.sessionToken()).accepted());
        assertEquals(null, grantRepo.findActiveGrant(ApiKeyService.subjectOf(mine.keyId())));
        assertTrue(keys.verify(theirs.key(), "203.0.113.5").accepted(), "other stations are untouched");
        assertEquals(0, keys.revokeAllForStation(stationId, "admin-sub"), "idempotent");
    }
}
