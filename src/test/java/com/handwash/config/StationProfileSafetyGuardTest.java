package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StationProfileSafetyGuardTest {

    private final StationProfileSafetyGuard guard = new StationProfileSafetyGuard();

    @Test
    void acceptsSingleMacLoopbackStationDefaults() {
        assertDoesNotThrow(() -> guard.validateConfiguration(safeEnvironment()));
    }

    @Test
    void actualStationProfileYamlFilesMatchTheLockedDecisionPolicy() throws IOException {
        YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
        var baseProperties = yamlLoader.load(
            "application", new ClassPathResource("application.yml")).get(0);

        for (String profile : new String[] {"station", "station-demo"}) {
            MockEnvironment environment = new MockEnvironment()
                // MockEnvironment does not fully reproduce Boot's nested URL placeholder resolution.
                .withProperty("spring.datasource.url",
                    "jdbc:h2:file:./.runtime/handwash;DB_CLOSE_ON_EXIT=FALSE");
            environment.setActiveProfiles(profile);
            environment.getPropertySources().addLast(baseProperties);
            environment.getPropertySources().addFirst(yamlLoader.load(
                "application-" + profile,
                new ClassPathResource("application-" + profile + ".yml")).get(0));

            assertDoesNotThrow(() -> guard.validateConfiguration(environment), profile);
            assertEquals("true", environment.getProperty("spring.web.resources.add-mappings"), profile);
            assertEquals("32", environment.getProperty("server.tomcat.threads.max"), profile);
            assertEquals("4", environment.getProperty("server.tomcat.threads.min-spare"), profile);
            assertEquals("16", environment.getProperty("server.tomcat.accept-count"), profile);
            assertEquals("512", environment.getProperty("server.tomcat.max-connections"), profile);
            assertEquals("3000", environment.getProperty(
                "handwash.intention.hand-presence-warmup-ms"), profile);
            assertEquals("500", environment.getProperty(
                "handwash.intention.hand-presence-max-gap-ms"), profile);
        }
    }

    @Test
    void rejectsUnreviewedTomcatConcurrencyOverridesInStationProfiles() {
        for (String profile : List.of("station", "station-demo")) {
            for (Map.Entry<String, String> override : Map.of(
                "server.tomcat.threads.max", "200",
                "server.tomcat.threads.min-spare", "1",
                "server.tomcat.accept-count", "100",
                "server.tomcat.max-connections", "8192").entrySet()) {
                MockEnvironment environment = safeEnvironment()
                    .withProperty(override.getKey(), override.getValue());
                environment.setActiveProfiles(profile);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard.validateConfiguration(environment), profile + ": " + override.getKey());
                org.junit.jupiter.api.Assertions.assertTrue(
                    failure.getMessage().contains(override.getKey()));
            }
        }
    }

    @Test
    void stationProfilesRejectRelaxingTheIndependentHandPresenceGate() {
        for (String profile : List.of("station", "station-demo")) {
            for (Map.Entry<String, String> override : Map.of(
                "handwash.intention.hand-presence-warmup-ms", "0",
                "handwash.intention.hand-presence-max-gap-ms", "1000").entrySet()) {
                MockEnvironment environment = safeEnvironment()
                    .withProperty(override.getKey(), override.getValue());
                environment.setActiveProfiles(profile);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard.validateConfiguration(environment), profile + ": " + override.getKey());
                org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
                    override.getKey()));
            }
        }
    }

    @Test
    void stationProfilesRejectAnOverrideThatWouldHideThePackagedDashboard() {
        for (String profile : List.of("station", "station-demo")) {
            MockEnvironment environment = safeEnvironment()
                .withProperty("spring.web.resources.add-mappings", "false");
            environment.setActiveProfiles(profile);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.validateConfiguration(environment), profile);
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
                "spring.web.resources.add-mappings"));
        }
    }

    @Test
    void rejectsStationStartupWhenReleaseSignatureIsNotProvisioned() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.oms.model-ready", "true")
            .withProperty("handwash.oms.input-enabled", "true");
        environment.setActiveProfiles("station");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateStationStartup(environment, Path.of(".")));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
            "HANDWASH_RELEASE_PUBLIC_KEY_PATH"));
    }

    @Test
    void rejectsAmbiguousActivationOfHospitalAndDemoProfiles() {
        MockEnvironment environment = safeEnvironment();
        environment.setActiveProfiles("station", "station-demo");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateStationStartup(environment, Path.of(".")));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("simultáneamente"));
    }

    @Test
    void rejectsCombiningAStationProfileWithAnyOtherSpringProfile(@TempDir Path projectRoot) {
        MockEnvironment environment = safeEnvironment();
        environment.setActiveProfiles("station-demo", "local");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateStationStartup(environment, projectRoot));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("otros perfiles"));
    }

    @Test
    void stationExternalOverrideBoundaryAllowsOnlySupervisorEnvironmentValues() {
        MockEnvironment approved = new MockEnvironment();
        approved.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "systemEnvironment", Map.ofEntries(
                Map.entry("SPRING_PROFILES_ACTIVE", "station-demo"),
                Map.entry("SERVER_ADDRESS", "127.0.0.1"),
                Map.entry("SERVER_PORT", "8080"),
                Map.entry("HANDWASH_CORS_ALLOWED_ORIGINS", "http://127.0.0.1:8080,http://localhost:8080"),
                Map.entry("HANDWASH_OMS_MODEL_READY", "false"),
                Map.entry("HANDWASH_OMS_INPUT_ENABLED", "false"),
                Map.entry("HANDWASH_INFERENCE_API_ENABLED", "false"),
                Map.entry("HANDWASH_PRODUCER_REQUIRE_V2", "true"),
                Map.entry("HANDWASH_SESSION_ACCESS_REQUIRED", "true"),
                Map.entry("HANDWASH_SESSION_MAX_ACTIVE_SESSIONS", "1"),
                Map.entry("HANDWASH_RELEASE_PUBLIC_KEY_PATH", "/outside/release.pub"))));

        assertDoesNotThrow(() -> StationProfileSafetyGuard.validateExternalOverrides(approved));
    }

    @Test
    void stationRejectsUnreviewedEnvironmentCliJsonAndExternalFileOverrides() {
        MockEnvironment environmentOverride = new MockEnvironment();
        environmentOverride.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "systemEnvironment", Map.of("SPRING_H2_CONSOLE_ENABLED", "true")));
        assertThrows(IllegalStateException.class,
            () -> StationProfileSafetyGuard.validateExternalOverrides(environmentOverride));

        MockEnvironment commandLineOverride = new MockEnvironment();
        commandLineOverride.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
            "--server.tomcat.max-connections=4096"));
        assertThrows(IllegalStateException.class,
            () -> StationProfileSafetyGuard.validateExternalOverrides(commandLineOverride));

        MockEnvironment jsonOverride = new MockEnvironment();
        jsonOverride.getPropertySources().addFirst(new MapPropertySource(
            "spring.application.json", Map.of("spring.h2.console.enabled", true)));
        assertThrows(IllegalStateException.class,
            () -> StationProfileSafetyGuard.validateExternalOverrides(jsonOverride));

        MockEnvironment fileOverride = new MockEnvironment();
        fileOverride.getPropertySources().addFirst(new MapPropertySource(
            "Config resource 'file [/tmp/station-overrides.yml]' via location 'optional:file:./'",
            Map.of("handwash.receptor.confidence-threshold", "0.9")));
        assertThrows(IllegalStateException.class,
            () -> StationProfileSafetyGuard.validateExternalOverrides(fileOverride));
    }

    @Test
    void stationRejectsMovementCalibrationEnvironmentOverridesEvenWhenValuesAreValid() {
        for (String variable : List.of(
            "HANDWASH_INTENTION_START_MINIMUM_NORMALIZED_MOVEMENT",
            "HANDWASH_INTENTION_STEP_MINIMUM_NORMALIZED_MOVEMENT")) {
            MockEnvironment environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of(variable, "0.05")));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> StationProfileSafetyGuard.validateExternalOverrides(environment), variable);
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(variable));
        }
    }

    @Test
    void springStationProfileInvokesReleaseGateBeforeContextRefreshCompletes() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("station");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "station-guard-test", Map.ofEntries(
                    Map.entry("server.address", "127.0.0.1"),
                    Map.entry("server.port", "8080"),
                    Map.entry("server.tomcat.threads.max", "32"),
                    Map.entry("server.tomcat.threads.min-spare", "4"),
                    Map.entry("server.tomcat.accept-count", "16"),
                    Map.entry("server.tomcat.max-connections", "512"),
                    Map.entry("spring.web.resources.add-mappings", "true"),
                    Map.entry("spring.task.scheduling.pool.size", "4"),
                    Map.entry("handwash.security.rate-limit-window-ms", "60000"),
                    Map.entry("handwash.security.session-create-per-window", "20"),
                    Map.entry("handwash.security.session-pair-per-window", "10"),
                    Map.entry("handwash.security.rate-limit-max-clients", "10000"),
                    Map.entry("handwash.security.rate-limit-cleanup-ms", "60000"),
                    Map.entry("handwash.session.expiration-check-ms", "1000"),
                    Map.entry("handwash.session.failed-attempt-persist-ms", "500"),
                    Map.entry("handwash.session.cleanup-check-ms", "60000"),
                    Map.entry("handwash.notification.flush-ms", "33"),
                    Map.entry("handwash.notification.send-timeout-ms", "3000"),
                    Map.entry("handwash.notification.max-websocket-clients", "256"),
                    Map.entry("handwash.notification.expired-summary-recovery-ms", "10000"),
                    Map.entry("handwash.receptor.confidence-threshold", "0.35"),
                    Map.entry("handwash.receptor.oms-confidence-threshold", "0.6"),
                    Map.entry("handwash.intention.confirmation-ms", "650"),
                    Map.entry("handwash.intention.max-gap-ms", "650"),
                    Map.entry("handwash.intention.min-observations", "3"),
                    Map.entry("handwash.intention.pause-reset-ms", "1500"),
                    Map.entry("handwash.intention.start-confidence", "0.75"),
                    Map.entry("handwash.intention.hand-presence-warmup-ms", "3000"),
                    Map.entry("handwash.intention.hand-presence-max-gap-ms", "500"),
                    Map.entry("handwash.session.access-required", "true"),
                    Map.entry("handwash.session.access-token-ttl-ms", "86400000"),
                    Map.entry("handwash.session.timeout-minutes", "5"),
                    Map.entry("handwash.session.terminal-retention-minutes", "30"),
                    Map.entry("handwash.session.max-detection-gap-ms", "1500"),
                    Map.entry("handwash.session.step-transition-confirm-max-gap-ms", "650"),
                    Map.entry("handwash.session.max-sessions", "128"),
                    Map.entry("handwash.session.max-active-sessions", "1"),
                    Map.entry("handwash.producer.require-v2", "true"),
                    Map.entry("handwash.inference.api-enabled", "false"),
                    Map.entry("handwash.oms.model-ready", "true"),
                    Map.entry("handwash.oms.input-enabled", "true"),
                    Map.entry("management.endpoints.web.exposure.include", "health"),
                    Map.entry("handwash.cors.allowed-origins",
                        "http://127.0.0.1:8080,http://localhost:8080"),
                    Map.entry("spring.datasource.url", "jdbc:h2:file:./.runtime/handwash"),
                    Map.entry("HANDWASH_RELEASE_PUBLIC_KEY_PATH", "")
                )));
            context.register(StationProfileSafetyGuard.class);

            IllegalStateException failure = assertThrows(IllegalStateException.class, context::refresh);
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
                "HANDWASH_RELEASE_PUBLIC_KEY_PATH"));
        }
    }

    @Test
    void springDemoProfileStartsWithoutReleaseKeyButKeepsStationConfigurationChecks() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("station-demo");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "station-demo-guard-test", Map.ofEntries(
                    Map.entry("server.address", "127.0.0.1"),
                    Map.entry("server.port", "8080"),
                    Map.entry("server.tomcat.threads.max", "32"),
                    Map.entry("server.tomcat.threads.min-spare", "4"),
                    Map.entry("server.tomcat.accept-count", "16"),
                    Map.entry("server.tomcat.max-connections", "512"),
                    Map.entry("spring.web.resources.add-mappings", "true"),
                    Map.entry("spring.task.scheduling.pool.size", "4"),
                    Map.entry("handwash.security.rate-limit-window-ms", "60000"),
                    Map.entry("handwash.security.session-create-per-window", "20"),
                    Map.entry("handwash.security.session-pair-per-window", "10"),
                    Map.entry("handwash.security.rate-limit-max-clients", "10000"),
                    Map.entry("handwash.security.rate-limit-cleanup-ms", "60000"),
                    Map.entry("handwash.session.expiration-check-ms", "1000"),
                    Map.entry("handwash.session.failed-attempt-persist-ms", "500"),
                    Map.entry("handwash.session.cleanup-check-ms", "60000"),
                    Map.entry("handwash.notification.flush-ms", "33"),
                    Map.entry("handwash.notification.send-timeout-ms", "3000"),
                    Map.entry("handwash.notification.max-websocket-clients", "256"),
                    Map.entry("handwash.notification.expired-summary-recovery-ms", "10000"),
                    Map.entry("handwash.receptor.confidence-threshold", "0.35"),
                    Map.entry("handwash.receptor.oms-confidence-threshold", "0.6"),
                    Map.entry("handwash.intention.confirmation-ms", "650"),
                    Map.entry("handwash.intention.max-gap-ms", "650"),
                    Map.entry("handwash.intention.min-observations", "3"),
                    Map.entry("handwash.intention.pause-reset-ms", "1500"),
                    Map.entry("handwash.intention.start-confidence", "0.75"),
                    Map.entry("handwash.intention.hand-presence-warmup-ms", "3000"),
                    Map.entry("handwash.intention.hand-presence-max-gap-ms", "500"),
                    Map.entry("handwash.session.access-required", "true"),
                    Map.entry("handwash.session.access-token-ttl-ms", "86400000"),
                    Map.entry("handwash.session.timeout-minutes", "5"),
                    Map.entry("handwash.session.terminal-retention-minutes", "30"),
                    Map.entry("handwash.session.max-detection-gap-ms", "1500"),
                    Map.entry("handwash.session.step-transition-confirm-max-gap-ms", "650"),
                    Map.entry("handwash.session.max-sessions", "128"),
                    Map.entry("handwash.session.max-active-sessions", "1"),
                    Map.entry("handwash.producer.require-v2", "true"),
                    Map.entry("handwash.inference.api-enabled", "false"),
                    Map.entry("handwash.oms.model-ready", "false"),
                    Map.entry("handwash.oms.input-enabled", "false"),
                    Map.entry("management.endpoints.web.exposure.include", "health"),
                    Map.entry("handwash.cors.allowed-origins",
                        "http://127.0.0.1:8080,http://localhost:8080"),
                    Map.entry("spring.datasource.url", "jdbc:h2:file:./.runtime/handwash"),
                    Map.entry("HANDWASH_RELEASE_PUBLIC_KEY_PATH", "")
                )));
            context.register(StationProfileSafetyGuard.class);

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(context::refresh);
        }
    }

    @Test
    void rejectsCustomLocalPortEvenWhenBothDashboardOriginsMatch() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("server.port", "18080")
            .withProperty("handwash.cors.allowed-origins",
                "http://127.0.0.1:18080,http://localhost:18080");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(environment));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("server.port"));
    }

    @Test
    void rejectsConfidenceOverrideThatCouldChangeWhichYoloEventsReachTheStateMachine() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.receptor.confidence-threshold", "0.01");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(environment));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
            "handwash.receptor.confidence-threshold"));
    }

    @Test
    void rejectsIntentionTimingOverrideThatCouldChangeSessionStartAndStepContinuity() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.intention.confirmation-ms", "100");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(environment));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(
            "handwash.intention.confirmation-ms"));
    }

    @Test
    void movementThresholdsMustBeNormalizedAndDemoCannotClaimClinicalCalibration() {
        for (String invalid : List.of("0", "-0.01", "1.01", "NaN", "Infinity")) {
            MockEnvironment environment = safeEnvironment()
                .withProperty("handwash.intention.start-minimum-normalized-movement", invalid);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.validateConfiguration(environment), invalid);
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("umbrales normalizados"));
        }

        MockEnvironment demo = safeEnvironment()
            .withProperty("handwash.intention.step-minimum-normalized-movement", "0.05");
        demo.setActiveProfiles("station-demo");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(demo));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("station-demo"));
    }

    @Test
    void rejectsOverridesToExpiryRateLimitsAndWebsocketDeliveryBounds() {
        for (String property : List.of(
            "handwash.session.expiration-check-ms",
            "handwash.security.session-create-per-window",
            "handwash.security.session-pair-per-window",
            "handwash.notification.flush-ms",
            "handwash.notification.send-timeout-ms",
            "handwash.notification.max-websocket-clients")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard.validateConfiguration(safeEnvironment().withProperty(property, "1")),
                property);
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains(property));
        }
    }

    @Test
    void rejectsNetworkBinding() {
        MockEnvironment environment = safeEnvironment().withProperty("server.address", "0.0.0.0");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsActuatorBindingOutsideLoopbackEvenWhenApplicationIsLocal() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("management.server.address", "0.0.0.0");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void acceptsSeparateActuatorBindingOnLoopback() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("management.server.address", "127.0.0.1")
            .withProperty("management.server.port", "18081");
        assertDoesNotThrow(() -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsDisabledSessionAccess() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.session.access-required", "false");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsMoreThanOneActiveSessionForTheSingleSinkStation() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.session.max-active-sessions", "2");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsStationProfileWhenActiveSessionLimitIsNotExplicit() {
        MockEnvironment environment = safeEnvironment();
        Map<?, ?> properties = (Map<?, ?>) environment.getPropertySources()
            .get("mockProperties").getSource();
        properties.remove("handwash.session.max-active-sessions");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsLegacyProducerProtocolInStationProfile() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.producer.require-v2", "false");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsDiagnosticInferenceApi() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.inference.api-enabled", "true");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsUnapprovedOmsModelReadinessOverride() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.oms.model-ready", "true");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void allowsOmsConfigurationOnlyForHospitalProfileAfterReleaseVerification() {
        MockEnvironment hospital = safeEnvironment()
            .withProperty("handwash.oms.model-ready", "true")
            .withProperty("handwash.oms.input-enabled", "true");
        hospital.setActiveProfiles("station");
        assertDoesNotThrow(() -> guard.validateConfiguration(hospital));
        assertDoesNotThrow(() -> guard.validateAuthorizedHospitalConfiguration(hospital));

        MockEnvironment demo = safeEnvironment()
            .withProperty("handwash.oms.model-ready", "true")
            .withProperty("handwash.oms.input-enabled", "true");
        demo.setActiveProfiles("station-demo");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(demo));

        MockEnvironment disabled = safeEnvironment();
        disabled.setActiveProfiles("station");
        assertThrows(IllegalStateException.class,
            () -> guard.validateAuthorizedHospitalConfiguration(disabled));
    }

    @Test
    void rejectsOmsInputEvenWhenModelReadyFlagIsFalse() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.oms.model-ready", "false")
            .withProperty("handwash.oms.input-enabled", "true");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void requiresHealthAsTheOnlyExposedActuatorEndpoint() {
        for (String exposed : List.of("health,metrics", "health,health", "metrics", "")) {
            MockEnvironment environment = safeEnvironment()
                .withProperty("management.endpoints.web.exposure.include", exposed);
            assertThrows(IllegalStateException.class,
                () -> guard.validateConfiguration(environment), "exposure=" + exposed);
        }
    }

    @Test
    void rejectsNonLocalCorsOrigin() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.cors.allowed-origins", "https://dashboard.hospital.example");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsCorsOriginsThatDoNotMatchConfiguredStationPort() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("server.port", "18080");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsAdditionalLoopbackCorsOriginsThatCouldReadOwnerCredentials() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("handwash.cors.allowed-origins",
                "http://127.0.0.1:8080,http://localhost:8080,http://localhost:5173");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));
    }

    @Test
    void rejectsRemoteOrSharedH2Database() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("spring.datasource.url", "jdbc:h2:tcp://127.0.0.1/~/handwash");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment));

        MockEnvironment shared = safeEnvironment()
            .withProperty("spring.datasource.url", "jdbc:h2:file:./.runtime/handwash;AUTO_SERVER=TRUE");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(shared));
    }

    @Test
    void rejectsH2InitializationCommandsAndDatabasePathsOutsideRuntimeDirectory() {
        for (String url : List.of(
            "jdbc:h2:file:./.runtime/handwash;INIT=RUNSCRIPT FROM 'file:///tmp/boot.sql'",
            "jdbc:h2:file:../../shared/handwash",
            "jdbc:h2:file:/tmp/handwash")) {
            MockEnvironment environment = safeEnvironment().withProperty("spring.datasource.url", url);
            assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(environment), url);
        }
    }

    @Test
    void rejectsRuntimeDirectoryThatRedirectsTheDatabaseOutsideTheProject(@TempDir Path projectRoot)
            throws IOException {
        Path externalDirectory = Files.createDirectory(projectRoot.resolve("external-data"));
        Files.createSymbolicLink(projectRoot.resolve(".runtime"), externalDirectory);

        assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(safeEnvironment(), projectRoot));
    }

    @Test
    void rejectsH2DatabaseFileSymlinks(@TempDir Path projectRoot) throws IOException {
        Path runtimeDirectory = Files.createDirectory(projectRoot.resolve(".runtime"));
        Path externalDatabase = Files.createFile(projectRoot.resolve("external.mv.db"));
        Files.createSymbolicLink(runtimeDirectory.resolve("handwash.mv.db"), externalDatabase);

        assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(safeEnvironment(), projectRoot));
    }

    @Test
    void rejectsH2DatabaseBasePathSymlink(@TempDir Path projectRoot) throws IOException {
        Path runtimeDirectory = Files.createDirectory(projectRoot.resolve(".runtime"));
        Path externalDirectory = Files.createDirectory(projectRoot.resolve("external-database"));
        Files.createSymbolicLink(runtimeDirectory.resolve("handwash"), externalDirectory);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(safeEnvironment(), projectRoot));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("base de la base H2"));
    }

    @Test
    void restrictsRuntimeDirectoryToCurrentUserWithoutChangingExistingFiles(@TempDir Path projectRoot)
            throws IOException {
        Path runtimeDirectory = Files.createDirectory(projectRoot.resolve(".runtime"));
        Path retainedLog = Files.writeString(runtimeDirectory.resolve("station.log"), "existing log");
        Files.setPosixFilePermissions(runtimeDirectory,
            PosixFilePermissions.fromString("rwxr-xr-x"));
        MockEnvironment environment = safeEnvironment();
        environment.setActiveProfiles("station-demo");

        guard.validateStationStartup(environment, projectRoot);

        assertEquals(PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(runtimeDirectory));
        assertEquals("existing log", Files.readString(retainedLog));
    }

    @Test
    void rejectsDatasourcePropertiesThatCouldReplaceValidatedConnectionOrExecuteSqlAtStartup() {
        for (Map.Entry<String, String> override : Map.of(
            "spring.datasource.hikari.jdbc-url",
            "jdbc:h2:file:./.runtime/handwash;INIT=RUNSCRIPT FROM 'file:///tmp/boot.sql'",
            "spring.datasource.hikari.username", "external-user",
            "spring.datasource.hikari.password", "unexpected-password",
            "spring.datasource.hikari.connection-init-sql", "RUNSCRIPT FROM 'file:///tmp/boot.sql'",
            "spring.sql.init.schema-locations[0]", "file:/tmp/unreviewed-schema.sql",
            "spring.datasource.jndi-name", "java:comp/env/jdbc/unreviewed").entrySet()) {
            MockEnvironment environment = safeEnvironment().withProperty(
                override.getKey(), override.getValue());
            assertThrows(IllegalStateException.class,
                () -> guard.validateConfiguration(environment), override.getKey());
        }

        MockEnvironment h2DriverOverride = safeEnvironment()
            .withProperty("spring.datasource.hikari.data-source-properties.MODE", "MySQL");
        assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(h2DriverOverride));
    }

    private MockEnvironment safeEnvironment() {
        return new MockEnvironment()
            .withProperty("server.address", "127.0.0.1")
            .withProperty("server.port", "8080")
            .withProperty("server.tomcat.threads.max", "32")
            .withProperty("server.tomcat.threads.min-spare", "4")
            .withProperty("server.tomcat.accept-count", "16")
            .withProperty("server.tomcat.max-connections", "512")
            .withProperty("spring.web.resources.add-mappings", "true")
            .withProperty("spring.task.scheduling.pool.size", "4")
            .withProperty("handwash.security.rate-limit-window-ms", "60000")
            .withProperty("handwash.security.session-create-per-window", "20")
            .withProperty("handwash.security.session-pair-per-window", "10")
            .withProperty("handwash.security.rate-limit-max-clients", "10000")
            .withProperty("handwash.security.rate-limit-cleanup-ms", "60000")
            .withProperty("handwash.session.expiration-check-ms", "1000")
            .withProperty("handwash.session.failed-attempt-persist-ms", "500")
            .withProperty("handwash.session.cleanup-check-ms", "60000")
            .withProperty("handwash.notification.flush-ms", "33")
            .withProperty("handwash.notification.send-timeout-ms", "3000")
            .withProperty("handwash.notification.max-websocket-clients", "256")
            .withProperty("handwash.notification.expired-summary-recovery-ms", "10000")
            .withProperty("handwash.receptor.confidence-threshold", "0.35")
            .withProperty("handwash.receptor.oms-confidence-threshold", "0.6")
            .withProperty("handwash.intention.confirmation-ms", "650")
            .withProperty("handwash.intention.max-gap-ms", "650")
            .withProperty("handwash.intention.min-observations", "3")
            .withProperty("handwash.intention.pause-reset-ms", "1500")
            .withProperty("handwash.intention.start-confidence", "0.75")
            .withProperty("handwash.intention.hand-presence-warmup-ms", "3000")
            .withProperty("handwash.intention.hand-presence-max-gap-ms", "500")
            .withProperty("handwash.session.access-required", "true")
            .withProperty("handwash.session.access-token-ttl-ms", "86400000")
            .withProperty("handwash.session.timeout-minutes", "5")
            .withProperty("handwash.session.terminal-retention-minutes", "30")
            .withProperty("handwash.session.max-detection-gap-ms", "1500")
            .withProperty("handwash.session.step-transition-confirm-max-gap-ms", "650")
            .withProperty("handwash.session.max-sessions", "128")
            .withProperty("handwash.session.max-active-sessions", "1")
            .withProperty("handwash.producer.require-v2", "true")
            .withProperty("handwash.inference.api-enabled", "false")
            .withProperty("handwash.oms.input-enabled", "false")
            .withProperty("management.endpoints.web.exposure.include", "health")
            .withProperty("handwash.cors.allowed-origins", "http://127.0.0.1:8080,http://localhost:8080")
            .withProperty("spring.datasource.url", "jdbc:h2:file:./.runtime/handwash;DB_CLOSE_ON_EXIT=FALSE");
    }
}
