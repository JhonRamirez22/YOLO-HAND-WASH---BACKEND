package com.handwash.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** No external identity provider on-premises: every bearer JWT is refused. API keys and local sessions authenticate. */
@Configuration
@Profile("onprem")
public class OnPremSecurityConfig {
    @Bean
    JwtDecoder cognitoJwtDecoder() { return rejecting(); }

    @Bean("cognitoIdJwtDecoder")
    JwtDecoder cognitoIdJwtDecoder() { return rejecting(); }

    private static JwtDecoder rejecting() {
        return token -> { throw new BadJwtException("Este despliegue no acepta JWT externos"); };
    }
}
