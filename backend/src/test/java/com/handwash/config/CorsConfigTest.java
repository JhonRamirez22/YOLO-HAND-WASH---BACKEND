package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsConfigTest {
    private static final String FRONTEND_ORIGIN = "https://frontend-proyecto-handwash.vercel.app";

    @Test
    void allowsPutForConfiguredFrontendOnly() {
        ExposedCorsRegistry registry = new ExposedCorsRegistry();
        new CorsConfig(FRONTEND_ORIGIN).addCorsMappings(registry);
        CorsConfiguration cors = registry.configurations().get("/api/**");

        assertTrue(cors.checkHttpMethod(HttpMethod.PUT).contains(HttpMethod.PUT));
        assertEquals(FRONTEND_ORIGIN, cors.checkOrigin(FRONTEND_ORIGIN));
        assertNull(cors.checkOrigin("https://untrusted.example"));
        assertNull(cors.checkHttpMethod(HttpMethod.TRACE));
    }

    private static final class ExposedCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
