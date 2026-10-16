package com.handwash.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

/**
 * API-key callers are shown to controllers as the same Jwt principal Cognito callers have, so the existing
 * grant, shift and producer-binding checks apply unchanged. The client ids below can never appear in a
 * Cognito token: the Cognito decoder only accepts the configured client ids.
 */
public final class SyntheticTokens {
    public static final String CONSOLE_CLIENT_ID = "handwash-api-key-console";

    private SyntheticTokens() {}

    public static JwtAuthenticationToken console(String keyId) {
        return token(ApiKeyService.subjectOf(keyId), CONSOLE_CLIENT_ID, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    }

    public static JwtAuthenticationToken producer(String keyId) {
        return token(ApiKeyService.subjectOf(keyId), ApiKeyService.producerClientIdOf(keyId),
            List.of(new SimpleGrantedAuthority("SCOPE_handwash/producer")));
    }

    /** A local ADMIN session: same ROLE_ADMIN the Cognito group used to grant, shown to controllers as a Jwt. */
    public static JwtAuthenticationToken admin(String subject) {
        return token(subject, "handwash-local-admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static JwtAuthenticationToken token(String subject, String clientId, List<GrantedAuthority> authorities) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("api-key").header("alg", "none").subject(subject)
            .claim("client_id", clientId).claim("token_use", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, authorities, subject);
    }
}
