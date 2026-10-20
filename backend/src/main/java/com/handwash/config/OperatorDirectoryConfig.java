package com.handwash.config;

import com.handwash.service.CognitoOperatorAccountDirectory;
import com.handwash.service.OperatorAccountDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("cloud")
public class OperatorDirectoryConfig {
    @Bean
    OperatorAccountDirectory operatorAccountDirectory(
        @Value("${handwash.security.operator-user-pool-id:}") String userPoolId,
        @Value("${AWS_REGION:sa-east-1}") String region) {
        if (userPoolId.isBlank()) return new OperatorAccountDirectory() {
            @Override public CreatedAccount create(String email) {
                throw new UnavailableException("El pool de operadores no está configurado.");
            }
            @Override public void disable(String subject) { /* nothing to disable */ }
            @Override public boolean available() { return false; }
        };
        return new CognitoOperatorAccountDirectory(region, userPoolId);
    }
}
