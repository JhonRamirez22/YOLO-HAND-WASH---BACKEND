package com.handwash.api.v1;

import com.handwash.api.v1.dto.DeploymentStatusResponse;
import com.handwash.service.ClinicalDecisionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/** Exposes a safe runtime mode so the dashboard cannot silently hide demo/development status. */
@RestController
@RequestMapping("/api/v1/deployment")
public final class DeploymentStatusController {
    private final Environment environment;
    private final ClinicalDecisionPolicy clinicalDecisionPolicy;

    public DeploymentStatusController(Environment environment) {
        this(environment, new ClinicalDecisionPolicy());
    }

    @Autowired
    public DeploymentStatusController(Environment environment,
                                      ClinicalDecisionPolicy clinicalDecisionPolicy) {
        this.environment = environment;
        this.clinicalDecisionPolicy = clinicalDecisionPolicy;
    }

    @GetMapping("/status")
    public DeploymentStatusResponse status() {
        if (hasProfile("station")) {
            return new DeploymentStatusResponse("HOSPITAL_PILOT",
                clinicalDecisionPolicy.isClinicalDecisionAllowed(),
                "PILOTO CONTROLADO: el release fue verificado para evaluación supervisada. "
                    + "No es autorización para producción clínica ni reemplaza el criterio del personal.");
        }
        if (hasProfile("station-demo")) {
            return new DeploymentStatusResponse("NON_CLINICAL_DEMO",
                clinicalDecisionPolicy.isClinicalDecisionAllowed(),
                "DEMOSTRACIÓN NO CLÍNICA: no usar para decisiones, registros ni evaluación de pacientes.");
        }
        return new DeploymentStatusResponse("DEVELOPMENT",
            clinicalDecisionPolicy.isClinicalDecisionAllowed(),
            "ENTORNO DE DESARROLLO: release no verificado; no usar para decisiones ni registros clínicos.");
    }

    private boolean hasProfile(String expected) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(expected::equals);
    }
}
