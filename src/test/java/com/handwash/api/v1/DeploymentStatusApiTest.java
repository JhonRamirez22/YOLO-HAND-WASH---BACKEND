package com.handwash.api.v1;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeploymentStatusApiTest {
    @Test
    void exposesNonClinicalModeToDashboardWithoutCredentials() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DeploymentStatusController(new MockEnvironment())).build();
        mvc.perform(get("/api/v1/deployment/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("DEVELOPMENT"))
            .andExpect(jsonPath("$.clinicalDecisionAllowed").value(false))
            .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("no usar")));
    }
}
