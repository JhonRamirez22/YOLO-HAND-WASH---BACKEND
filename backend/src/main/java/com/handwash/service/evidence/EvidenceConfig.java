package com.handwash.service.evidence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("cloud")
class EvidenceConfig {
    @Bean
    EvidenceStore evidenceStore(EvidenceProperties properties) {
        return properties.configured() ? new S3EvidenceStore(properties) : new UnavailableEvidenceStore();
    }
}
