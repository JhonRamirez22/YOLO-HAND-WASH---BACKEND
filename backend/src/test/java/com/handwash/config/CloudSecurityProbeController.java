package com.handwash.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CloudSecurityProbeController {
    @GetMapping("/api/v1/private-probe")
    ResponseEntity<Void> privateProbe() { return ResponseEntity.ok().build(); }

    @GetMapping("/api/v1/admin/security-probe")
    ResponseEntity<Void> adminProbe() { return ResponseEntity.ok().build(); }

    @GetMapping("/api/v1/operator/security-probe")
    ResponseEntity<Void> operatorProbe() { return ResponseEntity.ok().build(); }
}
