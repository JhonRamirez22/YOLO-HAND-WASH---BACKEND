package com.handwash.controller;

import com.handwash.api.v1.dto.SessionCreateRequest;
import com.handwash.api.v1.dto.SessionCreatedResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.handwash.repository.SessionAuditRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StepFeedbackIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionAuditRepository audit;

    private SessionCreatedResponse session() {
        return http.postForEntity("/api/v1/session", new SessionCreateRequest("DOMESTICO", "2"),
            SessionCreatedResponse.class).getBody();
    }

    private HttpStatus post(SessionCreatedResponse session, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return HttpStatus.valueOf(http.postForEntity("/api/v1/session/" + session.sessionId() + "/step-feedback",
            new HttpEntity<>(body, headers), String.class).getStatusCode().value());
    }

    private int events(String sessionId, String decision, String step) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events WHERE session_id = ? AND event_type = 'OPERATOR_STEP_FEEDBACK'
            AND decision_code = ? AND step_code = ?
            """, Integer.class, sessionId, decision, step);
    }

    @Test
    void confirmationsAndCorrectionsAreStoredAsCountsOnlyAuditEvents() {
        SessionCreatedResponse s = session();

        assertEquals(HttpStatus.OK, post(s, s.accessToken(), Map.of("paso", 3, "resultado", "CONFIRMADO")));
        assertEquals(HttpStatus.OK, post(s, s.accessToken(), Map.of("paso", 3, "resultado", "CORREGIDO", "pasoCorrecto", 5)));

        assertEquals(1, events(s.sessionId(), "CONFIRMED", "Paso3_Interdigitales"));
        assertEquals(1, events(s.sessionId(), "CORRECTED", "Paso3_Interdigitales"));
        assertEquals("CORRECTED_TO_Paso5_Pulgar", jdbc.queryForObject("""
            SELECT reason_code FROM wash_audit_events WHERE session_id = ? AND decision_code = 'CORRECTED'
            """, String.class, s.sessionId()));
    }

    @Test
    void invalidFeedbackIsRejectedAndNothingIsStored() {
        SessionCreatedResponse s = session();

        assertEquals(HttpStatus.BAD_REQUEST, post(s, s.accessToken(), Map.of("paso", 9, "resultado", "CONFIRMADO")));
        assertEquals(HttpStatus.BAD_REQUEST, post(s, s.accessToken(), Map.of("paso", 2, "resultado", "TAL_VEZ")));
        assertEquals(HttpStatus.BAD_REQUEST, post(s, s.accessToken(), Map.of("paso", 2, "resultado", "CORREGIDO")));
        assertEquals(HttpStatus.BAD_REQUEST, post(s, s.accessToken(), Map.of("paso", 2, "resultado", "CORREGIDO", "pasoCorrecto", 2)));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wash_audit_events WHERE session_id = ? AND event_type = 'OPERATOR_STEP_FEEDBACK'",
            Integer.class, s.sessionId()));
    }

    @Test
    void feedbackNeedsAccessToTheSession() {
        SessionCreatedResponse s = session();
        assertEquals(HttpStatus.UNAUTHORIZED, post(s, null, Map.of("paso", 1, "resultado", "CONFIRMADO")));
    }

    @Test
    void summaryCountsConfirmationsAndCorrectionsPerStep() {
        jdbc.update("DELETE FROM wash_audit_events WHERE event_type = 'OPERATOR_STEP_FEEDBACK'");
        SessionCreatedResponse s = session();
        post(s, s.accessToken(), Map.of("paso", 1, "resultado", "CONFIRMADO"));
        post(s, s.accessToken(), Map.of("paso", 1, "resultado", "CONFIRMADO"));
        post(s, s.accessToken(), Map.of("paso", 1, "resultado", "CORREGIDO", "pasoCorrecto", 2));

        var summary = audit.rehearsalSummary();

        var step1 = summary.stream().filter(row -> row.step().equals("Paso1_Palmas")).findFirst().orElseThrow();
        assertEquals(2L, step1.confirmed());
        assertEquals(1L, step1.corrected());
    }
}
