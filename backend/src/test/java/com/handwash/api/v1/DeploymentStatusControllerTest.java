package com.handwash.api.v1;

import com.handwash.api.v1.dto.DeploymentStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentStatusControllerTest {
    @Test
    void developmentModeNeverClaimsClinicalAuthorization() {
        DeploymentStatusResponse status = new DeploymentStatusController(new MockEnvironment()).status();

        assertEquals("DEVELOPMENT", status.mode());
        assertFalse(status.clinicalDecisionAllowed());
        assertTrue(status.notice().contains("no usar"));
    }

    @Test
    void demoModeIsExplicitlyNonClinical() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("station-demo");

        DeploymentStatusResponse status = new DeploymentStatusController(environment).status();

        assertEquals("NON_CLINICAL_DEMO", status.mode());
        assertFalse(status.clinicalDecisionAllowed());
        assertTrue(status.notice().contains("DEMOSTRACIÓN NO CLÍNICA"));
    }

    @Test
    void signedStationProfileIsLabeledAsPilotNotProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("station");

        DeploymentStatusResponse status = new DeploymentStatusController(environment).status();

        assertEquals("HOSPITAL_PILOT", status.mode());
        assertFalse(status.clinicalDecisionAllowed());
        assertTrue(status.notice().contains("No es autorización para producción clínica"));
    }
}
