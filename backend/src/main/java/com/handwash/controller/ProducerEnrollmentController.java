package com.handwash.controller;

import com.handwash.api.v1.dto.ApiErrorResponse;
import com.handwash.security.ClientAddress;
import com.handwash.security.enroll.ProducerEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public by design and powerless on its own: a PC with YOLO asks to be paired and waits for ADMIN's approval.
 * It receives no capability until an ADMIN approves its short code; requests are capped per address.
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/v1/producer/enroll")
public class ProducerEnrollmentController {
    public record StartRequest(String deviceName) {}
    public record PollRequest(String deviceCode) {}

    private final ProducerEnrollmentService enrollments;

    public ProducerEnrollmentController(ProducerEnrollmentService enrollments) { this.enrollments = enrollments; }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request, HttpServletRequest http) {
        if (request == null || request.deviceName() == null || request.deviceName().isBlank()) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("PC_NOMBRE_INVALIDO"));
        }
        try {
            var started = enrollments.start(request.deviceName(), ClientAddress.of(http));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deviceCode", started.deviceCode());
            body.put("userCode", started.userCode());
            body.put("expiresInSeconds", started.expiresInSeconds());
            body.put("intervalSeconds", 3);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException tooMany) {
            return ResponseEntity.status(429).body(ApiErrorResponse.of("DEMASIADAS_SOLICITUDES_DE_PC"));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of("PC_NOMBRE_INVALIDO"));
        }
    }

    @PostMapping("/poll")
    public ResponseEntity<?> poll(@RequestBody(required = false) PollRequest request) {
        var result = enrollments.poll(request == null ? null : request.deviceCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status());
        if (result.key() != null) body.put("key", result.key());
        return ResponseEntity.ok(body);
    }
}
