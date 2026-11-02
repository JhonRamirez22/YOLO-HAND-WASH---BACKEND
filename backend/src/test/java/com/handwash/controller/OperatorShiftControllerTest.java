package com.handwash.controller;

import com.handwash.model.StationRecord;
import com.handwash.model.StationShift;
import com.handwash.service.RequestRateLimiter;
import com.handwash.service.StationCredentialService;
import com.handwash.service.StationOperatorAccessService;
import com.handwash.service.StationShiftService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorShiftControllerTest {
    private static final String STATION = "11111111-1111-1111-1111-111111111111";
    private final StationOperatorAccessService access = mock(StationOperatorAccessService.class);
    private final StationCredentialService stations = mock(StationCredentialService.class);
    private final StationShiftService shifts = mock(StationShiftService.class);
    private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final OperatorShiftController controller =
        new OperatorShiftController(access, stations, shifts, limiter, "spa-client");
    private final StationRecord station = new StationRecord(STATION, "Puesto", 1L, "admin", null, null, null, null);
    private final Jwt human = jwt("spa-client");

    private static Jwt jwt(String clientId) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("op-sub").claim("client_id", clientId)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private void authorize() {
        var grant = new StationOperatorAccessService.GrantView("op-sub", "op@example.org", STATION, "Puesto", "admin", 1L, null);
        when(access.isHumanClient("spa-client", "spa-client")).thenReturn(true);
        when(access.activeGrant("op-sub")).thenReturn(grant);
        when(access.activeGrantForUpdate("op-sub")).thenReturn(grant);
        when(stations.activeStation(STATION)).thenReturn(station);
        when(limiter.allowSessionCreation(any())).thenReturn(true);
    }

    @Test
    void everyEndpointRejectsAnonymousMachineAndUnassignedCallers() {
        assertEquals(HttpStatus.FORBIDDEN, controller.status(null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.status(jwt("machine-client")).getStatusCode());
        when(access.isHumanClient("spa-client", "spa-client")).thenReturn(true);
        assertEquals(HttpStatus.FORBIDDEN, controller.start(human, new OperatorShiftController.ShiftRequest(8, "AGUA_Y_JABON"), http).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.stop(human).getStatusCode());
        verify(shifts, never()).start(any(), anyString(), anyInt(), any());
    }

    @Test
    void statusReportsWhetherAShiftIsOpen() {
        authorize();
        when(shifts.openShift(STATION)).thenReturn(Optional.empty());
        assertEquals(false, ((OperatorShiftController.ShiftView) controller.status(human).getBody()).active());

        when(shifts.openShift(STATION)).thenReturn(Optional.of(new StationShift("s", STATION, "AGUA_Y_JABON", "op-sub", 10, 20, null, null)));
        var view = (OperatorShiftController.ShiftView) controller.status(human).getBody();
        assertEquals(true, view.active());
        assertEquals(20L, view.endsAtEpochMs());
    }

    @Test
    void startMapsEveryOutcomeAndRespectsTheRateLimit() {
        authorize();
        var shift = new StationShift("s", STATION, "AGUA_Y_JABON", "op-sub", 10, 20, null, null);
        when(shifts.start(station, "op-sub", 8, "AGUA_Y_JABON"))
            .thenReturn(new StationShiftService.StartResult(StationShiftService.Outcome.STARTED, shift));
        when(shifts.start(station, "op-sub", 9, "AGUA_Y_JABON"))
            .thenReturn(new StationShiftService.StartResult(StationShiftService.Outcome.ALREADY_OPEN, shift));
        when(shifts.start(station, "op-sub", 99, "AGUA_Y_JABON"))
            .thenReturn(new StationShiftService.StartResult(StationShiftService.Outcome.INVALID, null));

        assertEquals(HttpStatus.OK, controller.start(human, new OperatorShiftController.ShiftRequest(8, "AGUA_Y_JABON"), http).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.start(human, new OperatorShiftController.ShiftRequest(9, "AGUA_Y_JABON"), http).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(human, new OperatorShiftController.ShiftRequest(99, "AGUA_Y_JABON"), http).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(human, new OperatorShiftController.ShiftRequest(null, "AGUA_Y_JABON"), http).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(human, null, http).getStatusCode());

        when(limiter.allowSessionCreation(any())).thenReturn(false);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, controller.start(human, new OperatorShiftController.ShiftRequest(8, "AGUA_Y_JABON"), http).getStatusCode());
    }

    @Test
    void stopReturnsNoContentOrNotFound() {
        authorize();
        when(shifts.stop(station, "op-sub")).thenReturn(true).thenReturn(false);
        assertEquals(HttpStatus.NO_CONTENT, controller.stop(human).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.stop(human).getStatusCode());
    }

    @Test
    void storageFailuresAreReportedAsUnavailable() {
        authorize();
        when(shifts.openShift(STATION)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.status(human).getStatusCode());
    }
}
