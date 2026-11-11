package com.handwash.service;

import com.handwash.model.WashStep;
import com.handwash.model.ProtocolProfile;
import com.handwash.model.WashSession;
import com.handwash.model.ProtocolType;
import com.handwash.repository.ProtocolProfileRepository;
import com.handwash.repository.SessionAuditRepository;
import com.handwash.strategy.ValidationRuleStrategyFactory;
import com.handwash.decorator.strategy.MetricsWashStrategyDecoratorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolProfileServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private SessionAuditService audit;
    private ProtocolProfileService service;
    private com.handwash.repository.ChangeControlRepository settings;
    private static final String REASON = "Ajuste tras la prueba piloto con voluntarios";

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        audit = new SessionAuditService(new SessionAuditRepository(jdbc));
        settings = new com.handwash.repository.ChangeControlRepository(jdbc);
        service = new ProtocolProfileService(new ProtocolProfileRepository(jdbc), settings, audit,
            new DataSourceTransactionManager(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void seededVersionOneIsInForce() {
        ProtocolProfile vigente = service.perfilVigente();

        assertEquals(1, vigente.version());
        assertEquals(42_000L, vigente.duracionTotalMs());
        assertEquals(ProtocolProfile.SISTEMA, vigente.creadoPor());
    }

    @Test
    void newVersionIsAppendedAuditedAndPutInForce() {
        Map<WashStep, Long> tiempos = new EnumMap<>(ProtocolProfile.uniforme(8_000L));

        ProtocolProfile creado = service.crearVersion(tiempos, 70_000L, "admin-sub", REASON).perfil();

        assertEquals(2, creado.version());
        assertEquals(creado, service.perfilVigente());
        assertEquals(2, service.historial(10).size());
        assertEquals(7_000L, service.historial(10).get(1).tiemposPasoMs().get(WashStep.PASO_1_PALMAS));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events
            WHERE event_type = 'PROTOCOL_PROFILE_VERSION_CREATED'
              AND actor_subject = 'admin-sub' AND reason_code = 'VERSION_2'
            """, Integer.class));
    }

    @Test
    void invalidProfileLeavesStorageAndCacheUntouched() {
        ProtocolProfile antes = service.perfilVigente();

        assertThrows(IllegalArgumentException.class,
            () -> service.crearVersion(ProtocolProfile.uniforme(5_000L), 60_000L, "admin-sub", REASON));

        assertEquals(antes, service.perfilVigente());
        assertEquals(1, service.historial(10).size());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'PROTOCOL_PROFILE_VERSION_CREATED'",
            Integer.class));
    }

    @Test
    void encounterRecordsTheProfileVersionUsedBySession() {
        service.crearVersion(ProtocolProfile.uniforme(9_000L), 80_000L, "admin-sub", REASON);
        ValidationRuleStrategyFactory factory = new ValidationRuleStrategyFactory(
            new MetricsWashStrategyDecoratorFactory(HandwashMetrics.noop()), service);
        ReflectionTestUtils.setField(factory, "balancedFrictionEnabled", true);
        String sessionId = UUID.randomUUID().toString();

        audit.createEncounter(new WashSession(sessionId, ProtocolType.AGUA_Y_JABON,
            factory.crear(ProtocolType.AGUA_Y_JABON)));

        assertEquals(2, audit.recentEncounters(1).get(0).protocolProfileVersion());
        assertEquals("AGUA_Y_JABON", audit.recentEncounters(1).get(0).protocolCode());
    }

    @Test
    void aChangeWithoutAReasonIsRefusedAndLeavesNoTrace() {
        for (String bad : new String[] {null, "", "   ", "corto", "x".repeat(201), "motivo\u0007con caracter de control"}) {
            assertThrows(IllegalArgumentException.class,
                () -> service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-sub", bad), String.valueOf(bad));
        }
        assertEquals(1, service.historial(10).size());
    }

    @Test
    void theReasonIsStoredNormalizedAndKeptOutOfTheAuditEvents() {
        var creado = service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-sub", "  Ajuste   por   fatiga de manos  ");

        assertEquals("Ajuste por fatiga de manos", creado.motivo());
        assertEquals("ACTIVE", creado.estado());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE reason_code LIKE '%fatiga%'", Integer.class));
    }

    @Test
    void rollbackCreatesANewVersionThatCopiesAnEarlierOneAndPutsItInForce() {
        service.crearVersion(ProtocolProfile.uniforme(9_000L), 80_000L, "admin-sub", REASON);

        var revertido = service.revertir(1, "admin-sub", "Los 9 s alargaban demasiado el lavado");

        assertEquals(3, revertido.perfil().version());
        assertEquals(1, revertido.revertidoDe());
        assertEquals(7_000L, revertido.perfil().tiemposPasoMs().get(WashStep.PASO_1_PALMAS));
        assertEquals(60_000L, revertido.perfil().umbralLentoMs());
        assertEquals(3, service.perfilVigente().version());
        assertEquals(3, service.historial(10).size(), "history is never rewritten");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'PROTOCOL_PROFILE_REVERTED'", Integer.class));
    }

    @Test
    void rollbackNeedsAReasonAndAKnownVersionThatWasInForce() {
        assertThrows(IllegalArgumentException.class, () -> service.revertir(1, "admin-sub", "no"));
        assertThrows(IllegalArgumentException.class, () -> service.revertir(99, "admin-sub", REASON));
        settings.setSecondApproverRequired(true, "admin-sub", 1L);
        var pending = service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-a", REASON);
        assertThrows(IllegalArgumentException.class, () -> service.revertir(pending.perfil().version(), "admin-b", REASON));
    }

    @Test
    void withASecondApproverRequiredAChangeWaitsAndOnlyAnotherAdminActivatesIt() {
        settings.setSecondApproverRequired(true, "admin-a", 1L);

        var pending = service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-a", REASON);

        assertEquals("PENDING", pending.estado());
        assertEquals(1, service.perfilVigente().version(), "a pending version does not apply");
        assertThrows(ProtocolProfileService.SameApproverException.class, () -> service.aprobar(2, "admin-a"));
        assertEquals(1, service.perfilVigente().version());

        var approved = service.aprobar(2, "admin-b");
        assertEquals("ACTIVE", approved.estado());
        assertEquals("admin-b", approved.decididoPor());
        assertEquals(2, service.perfilVigente().version());
        assertThrows(IllegalArgumentException.class, () -> service.aprobar(2, "admin-b"), "decided once");
    }

    @Test
    void aRejectedVersionNeverAppliesAndNumbersKeepGrowing() {
        settings.setSecondApproverRequired(true, "admin-a", 1L);
        service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-a", REASON);

        assertEquals("REJECTED", service.rechazar(2, "admin-b").estado());
        assertEquals(1, service.perfilVigente().version());

        settings.setSecondApproverRequired(false, "admin-a", 2L);
        var next = service.crearVersion(ProtocolProfile.uniforme(9_000L), 80_000L, "admin-a", REASON);
        assertEquals(3, next.perfil().version());
        assertEquals(3, service.perfilVigente().version());
    }

    @Test
    void withoutTheSwitchTheSameAdminDecidesAlone() {
        var created = service.crearVersion(ProtocolProfile.uniforme(8_000L), 70_000L, "admin-a", REASON);
        assertEquals("ACTIVE", created.estado());
    }
}
