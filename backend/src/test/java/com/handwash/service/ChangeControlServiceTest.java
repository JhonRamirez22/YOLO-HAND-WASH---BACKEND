package com.handwash.service;

import com.handwash.repository.ChangeControlRepository;
import com.handwash.repository.ProtocolProfileRepository;
import com.handwash.repository.SessionAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeControlServiceTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String DOC = "d".repeat(64);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ChangeControlService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        service = new ChangeControlService(new ChangeControlRepository(jdbc), new ProtocolProfileRepository(jdbc),
            new SessionAuditService(new SessionAuditRepository(jdbc)), () -> 5_000L, "yolo26n_7pasos", SHA_A, "dataset-1");
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private void encounterWithModel(String id, String sha, String modelVersion) {
        jdbc.update("""
            INSERT INTO wash_encounters (session_id, protocol_code, status, started_at_epoch_ms, model_version, model_sha256, dataset_version, attempts_restarted)
            VALUES (?, 'AGUA_Y_JABON', 'COMPLETED', 100, ?, ?, 'dataset-1', 0)""", id, modelVersion, sha);
    }

    @Test
    void theDeclaredReleaseIsRegisteredOnceAndStartsWithoutEvidence() {
        assertTrue(service.registerConfiguredRelease());
        assertFalse(service.registerConfiguredRelease(), "idempotent");

        var models = service.models();
        assertEquals(1, models.size());
        assertEquals(SHA_A, models.get(0).model().modelSha256());
        assertEquals(ChangeControlService.SIN_EVIDENCIA, models.get(0).evidencia());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'MODEL_RELEASE_REGISTERED'", Integer.class));
    }

    @Test
    void aModelSeenOnlyInEncountersAppearsAndAnUnconfiguredShaIsNotRegistered() {
        encounterWithModel("11111111-1111-1111-1111-111111111111", SHA_B, "yolo26-nuevo");
        encounterWithModel("22222222-2222-2222-2222-222222222222", SHA_B, "yolo26-nuevo");

        var models = service.models();
        assertEquals(1, models.size());
        assertEquals(2, models.get(0).model().encounters());
        assertEquals(ChangeControlService.SIN_EVIDENCIA, models.get(0).evidencia());

        var blank = new ChangeControlService(new ChangeControlRepository(jdbc), new ProtocolProfileRepository(jdbc),
            new SessionAuditService(new SessionAuditRepository(jdbc)), () -> 1L, "", "UNVERIFIED", "");
        assertFalse(blank.registerConfiguredRelease());
    }

    @Test
    void aValidationReportMovesOnlyItsOwnModelToWithReport() {
        service.registerConfiguredRelease();
        encounterWithModel("11111111-1111-1111-1111-111111111111", SHA_B, "yolo26-nuevo");

        var report = service.registerReport(SHA_A, 1, "Validación F5 piloto", "10 personas x 5 lavados, sensibilidad 0,93", DOC, "admin-sub");

        assertEquals(SHA_A, report.modelSha256());
        assertEquals(1, report.profileVersion());
        var byModel = service.models().stream().collect(java.util.stream.Collectors.toMap(m -> m.model().modelSha256(), m -> m.evidencia()));
        assertEquals(ChangeControlService.CON_INFORME, byModel.get(SHA_A));
        assertEquals(ChangeControlService.SIN_EVIDENCIA, byModel.get(SHA_B));
        assertTrue(service.profileHasReport(1));
        assertFalse(service.profileHasReport(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'VALIDATION_REPORT_REGISTERED'", Integer.class));
    }

    @Test
    void reportsAreValidatedAndNeverAnApproval() {
        service.registerConfiguredRelease();
        assertThrows(IllegalArgumentException.class, () -> service.registerReport("zz", null, "Título", "Resumen suficiente", DOC, "a"));
        assertThrows(IllegalArgumentException.class, () -> service.registerReport(SHA_A, null, "Título", "Resumen suficiente", "xyz", "a"));
        assertThrows(IllegalArgumentException.class, () -> service.registerReport(SHA_A, null, "T", "Resumen suficiente", DOC, "a"));
        assertThrows(IllegalArgumentException.class, () -> service.registerReport(SHA_A, null, "Título", "corto", DOC, "a"));
        assertThrows(IllegalArgumentException.class, () -> service.registerReport(SHA_B, null, "Título", "Resumen suficiente", DOC, "a"), "unknown model");
        assertThrows(IllegalArgumentException.class, () -> service.registerReport(SHA_A, 99, "Título", "Resumen suficiente", DOC, "a"), "unknown profile");
        assertEquals(0, service.reports(10).size());
    }

    @Test
    void theSecondApproverSwitchIsAuditedAndPersisted() {
        assertFalse(service.settings().secondApproverRequired());
        assertTrue(service.setSecondApproverRequired(true, "admin-sub").secondApproverRequired());
        assertEquals("admin-sub", service.settings().updatedBy());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE event_type = 'CHANGE_CONTROL_SETTINGS_CHANGED' AND decision_code = 'SECOND_APPROVER_ON'", Integer.class));
    }
}
