package com.handwash.controller;

import com.handwash.agent.SessionAuditObserver;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.SessionAuditService;
import com.handwash.service.SessionManager;
import com.handwash.service.StationCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CloudStationControllerTest {
    private final StationCredentialService stations = mock(StationCredentialService.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CloudStationController(stations, sessions,
            mock(SessionAuditService.class), mock(SessionAuditObserver.class),
            mock(RequestRateLimiter.class))).build();
    }

    @Test
    void missingOrRevokedStationCredentialCannotCreateASession() throws Exception {
        String requestBody = "{\"protocolo\":\"DOMESTICO\",\"producerProtocolVersion\":\"2\"}";

        when(stations.authenticate(null)).thenReturn(null);
        mvc.perform(post("/api/v1/stations/session")
                .header("X-Station-Request-Id", "a23f6a29-281a-4eac-9d7c-716d761b9618")
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isUnauthorized());

        when(stations.authenticate("revoked-station-credential")).thenReturn(null);
        mvc.perform(post("/api/v1/stations/session")
                .header("X-Station-Credential", "revoked-station-credential")
                .header("X-Station-Request-Id", "b68be4c6-421f-42f5-9720-2b6961e1265c")
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isUnauthorized());

        verify(sessions, never()).crearSesion(any(), anyBoolean());
    }
}
