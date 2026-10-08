package com.handwash.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Keeps an old packaged welcome page from masquerading as the dev dashboard. */
@RestController
@Profile("!station & !station-demo")
public final class DevelopmentRootController {
    @GetMapping("/")
    public ResponseEntity<Void> noEmbeddedDashboardInDevelopment() {
        return ResponseEntity.notFound().build();
    }
}
