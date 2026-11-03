package com.handwash.controller;

import com.handwash.model.StationRecord;
import com.handwash.service.SessionManager;
import com.handwash.service.SessionManager.AccessInfo;
import com.handwash.service.SessionManager.AccessRole;
import com.handwash.service.StationCredentialService;
import com.handwash.service.evidence.EvidenceUploadService;
import com.handwash.service.evidence.EvidenceUploadService.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationEvidenceControllerTest {
    private static final String SESSION = "33333333-3333-3333-3333-333333333333";
    private static final byte[] IMAGE = {1, 2, 3};

    private final StationCredentialService stations = mock(StationCredentialService.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final EvidenceUploadService uploads = mock(EvidenceUploadService.class);
    private final StationEvidenceController controller = new StationEvidenceController(stations, sessions, uploads);

    private void authenticated(String activeSession, AccessRole role) {
        StationRecord station = mock(StationRecord.class);
        when(station.activeSessionId()).thenReturn(activeSession);
        when(stations.authenticate("credential")).thenReturn(station);
        AccessInfo access = mock(AccessInfo.class);
        when(access.role()).thenReturn(role);
        when(sessions.autenticar(SESSION, "token")).thenReturn(access);
    }

    private int upload(byte[] body) {
        return controller.upload(SESSION, 2, "credential", "token", body).getStatusCode().value();
    }

    @Test
    void needsAKnownStation() {
        when(stations.authenticate("credential")).thenReturn(null);

        assertEquals(401, upload(IMAGE));
        verify(uploads, never()).upload(anyString(), anyInt(), any());
    }

    @Test
    void sessionMustBeTheStationsActiveOne() {
        authenticated("other-session", AccessRole.OWNER);

        assertEquals(403, upload(IMAGE));
        verify(uploads, never()).upload(anyString(), anyInt(), any());
    }

    @Test
    void needsTheOwnerCapabilityOfThatSession() {
        authenticated(SESSION, AccessRole.VIEWER);
        assertEquals(401, upload(IMAGE));

        when(sessions.autenticar(SESSION, "token")).thenReturn(null);
        assertEquals(401, upload(IMAGE));
        verify(uploads, never()).upload(anyString(), anyInt(), any());
    }

    @Test
    void rejectsMalformedAddressingAndEmptyBodies() {
        authenticated(SESSION, AccessRole.OWNER);

        assertEquals(400, upload(null));
        assertEquals(400, upload(new byte[0]));
        assertEquals(403, controller.upload("not-a-session", 2, "credential", "token", IMAGE).getStatusCode().value());
        assertEquals(403, controller.upload(SESSION, 0, "credential", "token", IMAGE).getStatusCode().value());
    }

    @Test
    void mapsEveryOutcomeToAnHttpStatus() {
        authenticated(SESSION, AccessRole.OWNER);
        int[] expected = {200, 403, 409, 409, 400, 503};
        Outcome[] outcomes = {Outcome.STORED, Outcome.DISABLED, Outcome.NOT_ELIGIBLE, Outcome.ALREADY_STORED,
            Outcome.INVALID_IMAGE, Outcome.UNAVAILABLE};
        for (int i = 0; i < outcomes.length; i++) {
            when(uploads.upload(SESSION, 2, IMAGE)).thenReturn(outcomes[i]);
            assertEquals(expected[i], upload(IMAGE), outcomes[i].name());
        }
    }
}
