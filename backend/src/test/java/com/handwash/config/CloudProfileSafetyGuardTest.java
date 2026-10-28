package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudProfileSafetyGuardTest {
    @TempDir Path tempDir;

    @Test
    void acceptsOnlyACompleteNonClinicalPostgresCognitoConfiguration() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        MockEnvironment env = validEnvironment(ca);

        assertDoesNotThrow(() -> new CloudProfileSafetyGuard().validateConfiguration(env));
    }

    @Test
    void rejectsHttpOriginsAndInsecureDatabaseTls() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();

        MockEnvironment httpOrigin = validEnvironment(ca)
            .withProperty("handwash.cors.allowed-origins", "http://example.org");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(httpOrigin));

        MockEnvironment weakTls = validEnvironment(ca)
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example/db?sslmode=require");
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(weakTls));
    }

    @Test
    void rejectsEnablingTheLocalOnnxInferenceApiInCloud() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");

        assertThrows(IllegalStateException.class, () -> new CloudProfileSafetyGuard()
            .validateConfiguration(validEnvironment(ca)
                .withProperty("handwash.inference.api-enabled", "true")));
    }

    @Test
    void rejectsSharedMigrationCredentialsAndUnverifiedModelHash() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();

        MockEnvironment sharedCredentials = validEnvironment(ca)
            .withProperty("spring.flyway.user", "app_user");
        assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(sharedCredentials));

        MockEnvironment unverifiedModel = validEnvironment(ca)
            .withProperty("handwash.audit.model-sha256", "UNVERIFIED");
        assertThrows(IllegalStateException.class,
            () -> guard.validateConfiguration(unverifiedModel));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("handwash.audit.model-version", "UNVERIFIED")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("handwash.audit.dataset-version", "UNVERIFIED")));
    }

    @Test
    void rejectsAmbiguousDatabaseUrlsAndNonCognitoIssuerHosts() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();
        String caPath = URLEncoder.encode(ca.toString(), StandardCharsets.UTF_8);
        String duplicateSslMode = "jdbc:postgresql://db.example/handwash?sslmode=disable&sslmode=verify-full&sslrootcert=" + caPath;

        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("spring.datasource.url", duplicateSslMode)));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "https://evilcognito-idp.example/sa-east-1_AbCdEf123")));
    }

    @Test
    void photoCaptureNeedsAnEncryptedBucketAndBoundedRetention() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();
        MockEnvironment complete = validEnvironment(ca)
            .withProperty("handwash.evidence.capture-enabled", "true")
            .withProperty("handwash.evidence.bucket", "evidence-bucket")
            .withProperty("handwash.evidence.kms-key-id", "arn:aws:kms:sa-east-1:1:key/abc")
            .withProperty("handwash.evidence.retention-days", "30");
        assertDoesNotThrow(() -> guard.validateConfiguration(complete));

        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment(ca)
            .withProperty("handwash.evidence.capture-enabled", "true")
            .withProperty("handwash.evidence.kms-key-id", "key")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment(ca)
            .withProperty("handwash.evidence.capture-enabled", "true")
            .withProperty("handwash.evidence.bucket", "evidence-bucket")));
        for (String retention : new String[]{"0", "91", "abc"}) {
            assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment(ca)
                .withProperty("handwash.evidence.capture-enabled", "true")
                .withProperty("handwash.evidence.bucket", "evidence-bucket")
                .withProperty("handwash.evidence.kms-key-id", "key")
                .withProperty("handwash.evidence.retention-days", retention)), retention);
        }
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(validEnvironment(ca)
            .withProperty("handwash.evidence.capture-enabled", "maybe")));
    }

    @Test
    void auditChainNeedsALongSecretAndASealBucketWithItsKey() throws Exception {
        Path ca = tempDir.resolve("rds-ca.pem");
        Files.writeString(ca, "test-ca");
        CloudProfileSafetyGuard guard = new CloudProfileSafetyGuard();

        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("handwash.audit.chain-secret", "short")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("handwash.audit.chain-secret", "")));
        assertThrows(IllegalStateException.class, () -> guard.validateConfiguration(
            validEnvironment(ca).withProperty("handwash.audit.seal-bucket", "seals")));
        assertDoesNotThrow(() -> guard.validateConfiguration(validEnvironment(ca)
            .withProperty("handwash.audit.seal-bucket", "seals").withProperty("handwash.audit.seal-kms-key-id", "key")));
    }

    private MockEnvironment validEnvironment(Path ca) {
        String caPath = URLEncoder.encode(ca.toString(), StandardCharsets.UTF_8);
        String dbUrl = "jdbc:postgresql://db.example:5432/handwash?sslmode=verify-full&sslrootcert=" + caPath;
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("cloud");
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
            .withProperty("spring.datasource.url", dbUrl)
            .withProperty("spring.flyway.url", dbUrl)
            .withProperty("spring.datasource.username", "app_user")
            .withProperty("spring.datasource.password", "app-secret")
            .withProperty("spring.flyway.user", "migration_user")
            .withProperty("spring.flyway.password", "migration-secret")
            .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "https://cognito-idp.sa-east-1.amazonaws.com/sa-east-1_AbCdEf123")
            .withProperty("handwash.security.cognito-client-id", "client-id")
            .withProperty("handwash.security.cognito-producer-client-id", "producer-client-id")
            .withProperty("handwash.cors.allowed-origins", "https://app.example.org")
            .withProperty("handwash.audit.chain-secret", "c".repeat(48))
            .withProperty("handwash.audit.model-version", "model-v1")
            .withProperty("handwash.audit.dataset-version", "dataset-v1")
            .withProperty("handwash.audit.model-sha256", "a".repeat(64));
    }
}
