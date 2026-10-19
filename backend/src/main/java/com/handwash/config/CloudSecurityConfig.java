package com.handwash.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;

import java.util.List;

@Configuration
@Profile("cloud")
@EnableWebSecurity
public class CloudSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain cloudSecurityFilterChain(HttpSecurity http, JwtDecoder cognitoJwtDecoder,
                                                  JwtAuthenticationConverter cognitoAuthorities,
                                                  org.springframework.beans.factory.ObjectProvider<
                                                      com.handwash.security.CredentialAuthenticator> credentialAuthenticators)
        throws Exception {
        java.util.List<com.handwash.security.CredentialAuthenticator> authenticators =
            credentialAuthenticators.orderedStream().toList();
        if (!authenticators.isEmpty()) {
            http.addFilterBefore(new com.handwash.security.CredentialAuthenticationFilter(authenticators),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        }
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/operator/key-login", "/api/v1/operator/phone-login").permitAll()
                // A PC with YOLO asks to be paired; it gets nothing until an ADMIN approves its short code.
                .requestMatchers(HttpMethod.POST, "/api/v1/producer/enroll", "/api/v1/producer/enroll/poll").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/admin/login", "/api/v1/auth/admin/logout").permitAll()
                // A phone camera paired by QR: the one-time code and then its camera-only token are the credentials.
                .requestMatchers(HttpMethod.POST, "/api/v1/camera/redeem", "/api/v1/camera/ticket", "/api/v1/camera/state").permitAll()
                .requestMatchers("/api/v1/operator/**").authenticated()
                .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/session")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/session/active")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login",
                    "/api/v1/deteccion", "/api/v1/session/*/producer-epoch")
                    .hasAuthority("SCOPE_handwash/producer")
                .requestMatchers(HttpMethod.POST, "/api/v1/producer/session")
                    .hasAuthority("SCOPE_handwash/producer")
                .requestMatchers("/api/v1/auth/dashboard-login", "/api/v1/session/pair",
                    "/api/v1/camera/**", "/api/v1/session/*/camera/link").denyAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/v1/deployment/status", "/api/v1/protocols",
                    "/api/v1/protocols/oms").permitAll()
                // These three station endpoints authenticate a one-time code or a hashed device credential in Java.
                .requestMatchers(HttpMethod.POST, "/api/v1/stations/enroll",
                    "/api/v1/stations/activate", "/api/v1/stations/session", "/api/v1/stations/resume",
                    "/api/v1/stations/evidence/*/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/stations/me").permitAll()
                .requestMatchers("/api/v1/stations/**").denyAll()
                // These routes authenticate their narrowly scoped session capability in the controller.
                .requestMatchers("/api/v1/session/*", "/api/v1/session/*/attempts",
                    "/api/v1/session/*/websocket-ticket", "/api/v1/session/*/webrtc-ticket").permitAll()
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource
                // Local ADMIN sessions (hwa_…) are authenticated by CredentialAuthenticationFilter, not by a JWT decoder.
                .bearerTokenResolver(request -> {
                    String token = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request);
                    return token != null && token.startsWith("hwa_") ? null : token;
                })
                .jwt(jwt -> jwt
                    .decoder(cognitoJwtDecoder)
                    .jwtAuthenticationConverter(cognitoAuthorities)));
        return http.build();
    }

    @Bean
    @Profile("!onprem")
    JwtDecoder cognitoJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${handwash.security.cognito-client-id}") String clientId,
        @Value("${handwash.security.cognito-producer-client-id}") String producerClientId,
        @Value("${handwash.security.operator-issuer-uri:}") String operatorIssuer,
        @Value("${handwash.security.operator-client-id:}") String operatorClientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> cognitoAccessToken = jwt ->
            validateCognitoClaims(jwt, clientId, producerClientId);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), cognitoAccessToken));
        if (operatorIssuer.isBlank() || operatorClientId.isBlank()) return decoder;

        NimbusJwtDecoder operatorDecoder = NimbusJwtDecoder.withIssuerLocation(operatorIssuer).build();
        operatorDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(operatorIssuer),
            jwt -> validateOperatorClaims(jwt, operatorClientId)));
        return token -> {
            String tokenIssuer;
            try {
                tokenIssuer = com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (java.text.ParseException unreadable) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Token ilegible", unreadable);
            }
            return operatorIssuer.equals(tokenIssuer) ? operatorDecoder.decode(token) : decoder.decode(token);
        };
    }

    static OAuth2TokenValidatorResult validateOperatorClaims(Jwt jwt, String operatorClientId) {
        boolean valid = "access".equals(jwt.getClaimAsString("token_use"))
            && operatorClientId.equals(jwt.getClaimAsString("client_id"))
            && jwt.getSubject() != null && !jwt.getSubject().isBlank();
        return valid ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Se requiere un access token del pool de operadores.", null));
    }

    @Bean("cognitoIdJwtDecoder")
    @Profile("!onprem")
    JwtDecoder cognitoIdJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${handwash.security.cognito-client-id}") String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> idToken = jwt -> {
            boolean valid = "id".equals(jwt.getClaimAsString("token_use"))
                && jwt.getAudience().contains(clientId)
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
            return valid ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "Se requiere un ID token Cognito con correo verificado.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), idToken));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter cognitoAuthorities(
        @Value("${handwash.security.operator-issuer-uri:}") String operatorIssuer) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new java.util.ArrayList<>(scopes.convert(jwt));
            // Operator-pool logins never receive ADMIN, whatever groups their token claims.
            if (!operatorIssuer.isBlank() && jwt.getIssuer() != null
                && operatorIssuer.equals(jwt.getIssuer().toString())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
                return authorities;
            }
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            if (groups != null) {
                if (groups.contains("ADMIN")) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                if (groups.contains("OPERATOR")) authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
            }
            return authorities;
        });
        return converter;
    }

    static OAuth2TokenValidatorResult validateCognitoClaims(Jwt jwt, String clientId) {
        return validateCognitoClaims(jwt, clientId, null);
    }

    static OAuth2TokenValidatorResult validateCognitoClaims(
        Jwt jwt, String userClientId, String producerClientId) {
        String clientId = jwt.getClaimAsString("client_id");
        boolean producerClient = producerClientId != null && !producerClientId.isBlank()
            && producerClientId.equals(clientId);
        boolean knownClient = userClientId.equals(clientId) || producerClient;
        boolean validSubject = jwt.getSubject() != null && !jwt.getSubject().isBlank()
            && (!producerClient || clientId.equals(jwt.getSubject()));
        boolean valid = "access".equals(jwt.getClaimAsString("token_use"))
            && knownClient
            && validSubject;
        return valid ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Se requiere un access token de Cognito válido.", null));
    }
}
