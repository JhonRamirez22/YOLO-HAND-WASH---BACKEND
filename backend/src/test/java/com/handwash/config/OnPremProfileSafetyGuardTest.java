package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Seam: CloudProfileSafetyGuard with the on-premises profile (PC at home, PostgreSQL on a private network, no AWS). */
class OnPremProfileSafetyGuardTest {
    @TempDir Path tempDir;

    private final CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();

    private MockEnvironment validEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("cloud", "onprem");
        return env
            .withProperty("server.address", "0.0.0.0")
            .withProperty("handwash.deployment.mode", "NON_CLINICAL_DEMO")
            .withProperty("spring.sql.init.mode", "never")
            .withProperty("spring.flyway.clean-disabled", "true")
            .withProperty("handwash.oms.model-ready", "false")
            .withProperty("handwash.oms.input-enabled", "false")
            .withProperty("handwash.evidence.capture-enabled", "false")
            .withProperty("handwash.video.remote-enabled", "false")
            .withProperty("handwash.inference.api-enabled", "false")
            .withProperty("handwash.security.producer-jwt-enabled", "true")
            .withProperty("handwash.producer.require-v2", "true")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/handwash")
            .withProperty("spring.flyway.url", "jdbc:postgresql://db:5432/handwash")
            .withProperty("spring.datasource.username", "handwash_runtime")
            .withProperty("spring.datasource.password", "runtime-secret-0123456789")
            .withProperty("spring.flyway.user", "handwash_migrator")
            .withProperty("spring.flyway.password", "migrator-secret-0123456789")
            .withProperty("handwash.cors.allowed-origins", "https://frontend-proyecto-handwash.vercel.app")
            .withProperty("handwash.audit.chain-secret", "c".repeat(48))
            .withProperty("handwash.audit.seal-dir", tempDir.toString())
            .withProperty("handwash.audit.model-version", "model-v1")
            .withProperty("handwash.audit.dataset-version", "dataset-v1")
            .withProperty("handwash.audit.model-sha256", "a".repeat(64));
    }

    @Test
    void acceptsACompleteOnPremisesConfigurationWithoutAnyAwsPiece() {
        assertDoesNotThrow(() -> guard.validateConfiguration(validEnvironment()));
        assertDoesNotThrow(() -> guard.validateConfiguration(
            validEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/handwash")
                .withProperty("spring.flyway.url", "jdbc:postgresql://localhost:5432/handwash")));
    }

    @Test
    void aDatabaseWithoutTlsIsOnlyAcceptedOnTheLocalNetworkAndOnlyOnPremises() {
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment()
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.org:5432/handwash")
            .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.org:5432/handwash")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment()
            .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/handwash?sslmode=disable")));
        MockEnvironment cloudOnly = validEnvironment();
        cloudOnly.setActiveProfiles("cloud");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(cloudOnly));
    }

    @Test
    void sealsNeedAnAbsoluteWritableDirectoryAndPhotosStayOff() {
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.audit.seal-dir", "")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.audit.seal-dir", "relative/seals")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.audit.seal-dir", tempDir.resolve("missing").toString())));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.evidence.capture-enabled", "true")));
    }

    @Test
    void theRestOfTheCloudRulesStillApply() {
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.cors.allowed-origins", "http://example.org")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.audit.chain-secret", "short")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("spring.flyway.user", "handwash_runtime")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment().withProperty("handwash.inference.api-enabled", "true")));
    }
}
