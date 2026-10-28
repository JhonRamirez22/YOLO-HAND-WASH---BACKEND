package com.handwash.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the REAL application with the cloud profile (all controllers, services, schedulers, Flyway V1-V12 on H2,
 * Spring Security with the real filter chain) so a wiring mistake in any cloud-only bean fails the build instead of the
 * deployed service. Only the infrastructure that cannot exist in CI is replaced: PostgreSQL/TLS (H2; the safety guard
 * that demands verify-full is tested separately in CloudProfileSafetyGuardTest) and the Cognito discovery call (lazy).
 */
@SpringBootTest(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.url=jdbc:h2:mem:cloud-context;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.url=jdbc:h2:mem:cloud-context;DB_CLOSE_DELAY=-1",
    "spring.flyway.user=sa", "spring.flyway.password=",
    "spring.sql.init.mode=never",
    "COGNITO_ISSUER_URI=https://cognito-idp.sa-east-1.amazonaws.com/sa-east-1_TESTPOOL",
    "COGNITO_CLIENT_ID=spaclientid0123456789", "COGNITO_PRODUCER_CLIENT_ID=producerclient0123456789",
    "HANDWASH_CORS_ALLOWED_ORIGINS=https://frontend.example.org",
    "HANDWASH_MODEL_VERSION=model-test", "HANDWASH_MODEL_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "HANDWASH_DATASET_VERSION=dataset-test",
    "HANDWASH_AUDIT_CHAIN_SECRET=kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk",
    "handwash.session.token-ttl-ms=3600000"
})
@ActiveProfiles("cloud")
class CloudProfileContextTest {
    @Autowired ApplicationContext context;
    // Building the real decoders calls Cognito's discovery endpoint; everything around them stays real.
    @MockitoBean(name = "cognitoJwtDecoder") JwtDecoder accessTokenDecoder;
    @MockitoBean(name = "cognitoIdJwtDecoder") JwtDecoder idTokenDecoder;

    /** Same bean name as the real guard: it validates RDS TLS and secrets, which this test deliberately does not have. */
    @TestConfiguration
    static class SkipInfrastructureGuard {
        @Bean(name = "cloudProfileSafetyGuard")
        static BeanFactoryPostProcessor noGuard() { return beanFactory -> { }; }
    }

    @Test
    void theCloudProfileStartsWithEveryControllerAndServiceWired() {
        assertNotNull(context.getBean("adminWashReportController"));
        assertNotNull(context.getBean("adminWashStatsController"));
        assertNotNull(context.getBean("adminAuditChainController"));
        assertNotNull(context.getBean("adminChangeControlController"));
        assertNotNull(context.getBean("stationEvidenceController"));
        assertTrue(context.getBeanDefinitionCount() > 150, "beans: " + context.getBeanDefinitionCount());
    }
}
