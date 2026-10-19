package com.handwash.config;

import com.handwash.repository.StationOperatorAccessRepository;
import com.handwash.security.ApiKeyRepository;
import com.handwash.security.ApiKeyService;
import com.handwash.service.SessionAuditService;
import com.handwash.service.StationCredentialService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("cloud")
public class ApiKeyConfig {
    @Bean
    ApiKeyService apiKeyService(ApiKeyRepository repository, StationCredentialService stations,
                                StationOperatorAccessRepository grants, SessionAuditService audit) {
        return new ApiKeyService(repository, stations, grants, audit);
    }
}
