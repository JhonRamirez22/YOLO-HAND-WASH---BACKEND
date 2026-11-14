package com.handwash.service.evidence;

import com.handwash.service.SessionAuditService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EvidenceSettingsServiceTest {
    private EmbeddedDatabase database;
    private EvidenceSettingsRepository repository;
    private final SessionAuditService audit = mock(SessionAuditService.class);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        repository = new EvidenceSettingsRepository(new JdbcTemplate(database));
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    private static EvidenceProperties props(boolean deployment, String bucket, String kms) {
        return new EvidenceProperties(deployment, bucket, kms, 30, "sa-east-1");
    }

    private EvidenceSettingsService service(EvidenceProperties properties) {
        return new EvidenceSettingsService(properties, repository, audit, () -> 5_000L);
    }

    @Test
    void startsOffEvenWhenTheDeploymentAllowsPhotos() {
        var view = service(props(true, "bucket", "kms-key")).view();

        assertFalse(view.switchOn());
        assertFalse(view.captureActive());
        assertTrue(view.deploymentEnabled());
        assertEquals(30, view.retentionDays());
    }

    @Test
    void captureNeedsDeploymentConfigurationAndTheAdminSwitch() {
        var allowed = service(props(true, "bucket", "kms-key"));
        allowed.setEnabled(true, "admin-sub");
        assertTrue(allowed.captureActive());

        assertFalse(service(props(false, "bucket", "kms-key")).captureActive(), "deployment flag off wins over the switch");
        assertFalse(service(props(true, "", "kms-key")).captureActive(), "no bucket");
        assertFalse(service(props(true, "bucket", "")).captureActive(), "no customer-managed key");
    }

    @Test
    void killSwitchTakesEffectImmediatelyAndIsAudited() {
        var service = service(props(true, "bucket", "kms-key"));
        service.setEnabled(true, "admin-sub");
        service.setEnabled(false, "admin-sub");

        assertFalse(service.captureActive());
        verify(audit).recordStationEvent(null, "admin-sub", "ADMIN_EVIDENCE_ENABLED", "ENABLED", null);
        verify(audit).recordStationEvent(null, "admin-sub", "ADMIN_EVIDENCE_DISABLED", "DISABLED", null);
        assertEquals("admin-sub", service.view().updatedBy());
        assertEquals(5_000L, service.view().updatedAtEpochMs());
    }

    @Test
    void cannotBeSwitchedOnWithoutADeploymentThatAllowsPhotos() {
        var notConfigured = service(props(true, "", ""));
        assertThrows(IllegalStateException.class, () -> notConfigured.setEnabled(true, "admin-sub"));
        var deploymentOff = service(props(false, "bucket", "kms-key"));
        assertThrows(IllegalStateException.class, () -> deploymentOff.setEnabled(true, "admin-sub"));
        verifyNoInteractions(audit);
        assertFalse(repository.get().captureEnabled());
        deploymentOff.setEnabled(false, "admin-sub");
    }

    @Test
    void retentionIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProperties(true, "b", "k", 0, "sa-east-1"));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceProperties(true, "b", "k", 91, "sa-east-1"));
    }
}
