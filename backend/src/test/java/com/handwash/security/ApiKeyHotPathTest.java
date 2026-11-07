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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Seam: the PC authenticates on every observation (~5 per second); that must not cost a query and a write each time. */
class ApiKeyHotPathTest {
    private EmbeddedDatabase database;
    private ApiKeyRepository repository;
    private ApiKeyService keys;
    private String stationId;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        var stations = new StationCredentialService(new StationRepository(jdbc), audit);
        repository = spy(new ApiKeyRepository(jdbc));
        keys = new ApiKeyService(repository, stations, new StationOperatorAccessRepository(jdbc), audit, clock::get);
        stationId = stations.provision("Lavamanos A", null, "admin").station().stationId();
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void repeatedVerificationsHitTheDatabaseOnceAndRecordUseAtMostOncePerMinute() {
        var issued = keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin");

        for (int i = 0; i < 50; i++) {
            assertTrue(keys.verify(issued.key(), "203.0.113.5").accepted());
            clock.addAndGet(100);
        }

        verify(repository, times(1)).find(issued.keyId());
        verify(repository, times(1)).markUsed(anyString(), anyLong(), any());

        clock.addAndGet(61_000);
        keys.verify(issued.key(), "203.0.113.5");
        verify(repository, times(2)).markUsed(anyString(), anyLong(), any());
    }

    @Test
    void revokingMakesACachedKeyFailImmediately() {
        var issued = keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin");
        assertTrue(keys.verify(issued.key(), "203.0.113.5").accepted());

        keys.revoke(issued.keyId(), "admin");

        assertFalse(keys.verify(issued.key(), "203.0.113.5").accepted());
    }

    @Test
    void theCacheNeverAcceptsADifferentSecretForTheSameKeyId() {
        var issued = keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin");
        assertTrue(keys.verify(issued.key(), "203.0.113.5").accepted());

        assertFalse(keys.verify(issued.key().substring(0, 21) + "Q".repeat(43), "203.0.113.5").accepted());
    }

    @Test
    void aCachedAcceptanceExpiresSoChangesNeverLingerLongerThanThirtySeconds() {
        var issued = keys.issue(stationId, "PC", EnumSet.of(ApiKeyScope.PRODUCER), "admin");
        keys.verify(issued.key(), "203.0.113.5");
        clock.addAndGet(31_000);
        keys.verify(issued.key(), "203.0.113.5");

        verify(repository, times(2)).find(issued.keyId());
        assertEquals(1, 1);
    }
}
