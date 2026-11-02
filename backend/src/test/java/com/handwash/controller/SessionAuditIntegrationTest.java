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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SessionAuditIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sessionCreationAndOwnerDeletionArePersistedAsMetadataOnlyAuditEvents() {
        ResponseEntity<SessionCreatedResponse> created = http.postForEntity("/api/v1/session",
            new SessionCreateRequest("DOMESTICO", "2"), SessionCreatedResponse.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        SessionCreatedResponse session = created.getBody();
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM wash_encounters WHERE session_id = ?", Integer.class,
            session.sessionId()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(session.accessToken());
        ResponseEntity<String> deleted = http.exchange("/api/v1/session/" + session.sessionId(),
            HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertEquals("DELETED", jdbc.queryForObject(
            "SELECT status FROM wash_encounters WHERE session_id = ?", String.class,
            session.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events
            WHERE session_id = ? AND event_type = 'SESSION_CREATED'
            """, Integer.class, session.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events
            WHERE session_id = ? AND event_type = 'SESSION_DELETE_REQUESTED'
            """, Integer.class, session.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM wash_audit_events
            WHERE session_id = ? AND event_type = 'SESSION_DELETED'
            """, Integer.class, session.sessionId()));
    }
}
